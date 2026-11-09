package analyzer.binding;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.SourceLocation;
import analyzer.model.ValueSource;
import analyzer.model.VariableChange;
import analyzer.model.VariableInfo;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 분석된 메서드의 지역 변수 선언(전부)과 "선언 뒤에 값이 바뀌는" 지역 변수·파라미터의 변경 이력(VC*)을 만든다.
 * 값이 바뀌지 않는 지역 변수는 선언(INIT) 이력 하나뿐 — 흐름도·호출 트리에 선언 코드(boolean data = false)를 보이기 위함.
 * <ul>
 *   <li>대상 변경: 대입(=), 복합 대입(+= -= …), ++/--. 필드(this.x, 선언 없는 이름)는 대상이 아니다</li>
 *   <li>for 헤더 변수(int i …; i++)와 향상된 for 변수는 반복 제어라 제외</li>
 *   <li>첫 이력은 선언 초기값(INIT) 또는 파라미터(PARAM). 조건·소속 블록·식 안 호출·값 출처(자기 자신 제외)를 남긴다</li>
 * </ul>
 */
public final class VariableStateCollector {

    private final AnalysisResult r;
    private final Map<String, MethodDeclaration> declarations;
    private final SourceTracer tracer;

    public VariableStateCollector(AnalysisResult r, Map<String, MethodDeclaration> declarations,
                                  Map<MethodCallExpr, List<RequestParameterInfo>> paramsByCall,
                                  Map<String, MethodCallExpr> callExprs) {
        this.r = r;
        this.declarations = declarations;
        this.tracer = new SourceTracer(r, declarations, paramsByCall, callExprs);
    }

    public void collect() {
        for (MethodInfo m : r.methods()) {
            MethodDeclaration md = declarations.get(m.id);
            if (!m.analyzed || md == null || md.getBody().isEmpty()) {
                continue;
            }
            collect(m, md);
        }
    }

    private void collect(MethodInfo m, MethodDeclaration md) {
        // 이름 → 변경 노드 (소스 순서)
        Map<String, List<Expression>> changes = new LinkedHashMap<>();
        md.getBody().get().walk(Node.TreeTraversal.PREORDER, n -> {
            String name = null;
            if (n instanceof AssignExpr a && a.getTarget() instanceof NameExpr t) {
                name = t.getNameAsString();
            } else if (n instanceof UnaryExpr u && u.getExpression() instanceof NameExpr t && isIncDec(u)) {
                name = t.getNameAsString();
            }
            if (name != null && !inForHeader((Expression) n)) {
                changes.computeIfAbsent(name, k -> new ArrayList<>()).add((Expression) n);
            }
        });
        java.util.Set<VariableDeclarator> covered = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var e : changes.entrySet()) {
            String name = e.getKey();
            VariableDeclarator decl = md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name)).stream()
                    .filter(v -> ownLocal(v, md)).findFirst().orElse(null);
            Parameter param = md.getParameterByName(name).orElse(null);
            if (decl == null && param == null) {
                continue; // 필드 등 — 메서드 지역 상태가 아님
            }
            if (decl != null && (decl.getParentNode().flatMap(Node::getParentNode).orElse(null) instanceof ForStmt
                    || decl.getParentNode().flatMap(Node::getParentNode).orElse(null) instanceof ForEachStmt)) {
                continue; // for (int i …) / for (X x : xs)
            }
            if (decl != null) {
                covered.add(decl);
            }
            VariableInfo v = variable(m, name, decl, param);
            List<Expression> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingInt((Expression x) -> x.getBegin().map(p -> p.line).orElse(0))
                    .thenComparingInt(x -> x.getBegin().map(p -> p.column).orElse(0)));
            for (Expression x : list) {
                VariableChange c = base(v, m, x);
                c.expression = ConditionText.text(x);
                if (x instanceof AssignExpr a) {
                    c.operator = a.getOperator().asString();
                    c.op = a.getOperator() == AssignExpr.Operator.ASSIGN ? "ASSIGN" : "COMPOUND";
                    value(c, a.getValue(), name, m);
                } else {
                    UnaryExpr u = (UnaryExpr) x;
                    boolean inc = u.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT
                            || u.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT;
                    c.op = inc ? "INCREMENT" : "DECREMENT";
                    c.operator = inc ? "++" : "--";
                }
                calls(c, m);
                add(v, c);
            }
        }
        // 값이 바뀌지 않는 지역 변수도 선언(초기값)은 흐름에 보여야 한다 — boolean data = false, String label; …
        for (VariableDeclarator d : md.getBody().get().findAll(VariableDeclarator.class)) {
            if (!covered.contains(d) && ownLocal(d, md)) {
                variable(m, d.getNameAsString(), d, null);
            }
        }
    }

    /**
     * 변수 정보 + 첫 이력(선언 INIT 또는 파라미터 PARAM). 선언 코드는 "타입 이름 = 값" (int a = 0, b = 1 이면 각각)
     */
    private VariableInfo variable(MethodInfo m, String name, VariableDeclarator decl, Parameter param) {
        VariableInfo v = new VariableInfo();
        v.id = r.nextId("V");
        v.methodId = m.id;
        v.name = name;
        v.kind = decl != null ? "LOCAL" : "PARAMETER";
        v.type = decl != null ? decl.getTypeAsString() : param.getTypeAsString();
        Node declNode = decl != null ? decl : param;
        v.location = SourceLocation.of(declNode, m.location.file());
        r.index(v.id, v);
        r.variables.add(v);

        VariableChange first = base(v, m, declNode);
        if (decl != null) {
            first.op = "INIT";
            first.expression = v.type + " " + ConditionText.text(decl);
            decl.getInitializer().ifPresent(init -> value(first, init, name, m));
            calls(first, m);
        } else {
            first.op = "PARAM";
            first.expression = ConditionText.text(param);
        }
        add(v, first);
        return v;
    }

    /**
     * 이 메서드 자신의 지역 변수 선언인지: for 헤더·향상된 for 변수, 람다·익명/로컬 클래스 안 선언은 제외
     * (람다 본문은 나중에 실행되고, 익명 클래스 본문은 분석하지 않는다).
     */
    private static boolean ownLocal(VariableDeclarator v, MethodDeclaration md) {
        Node gp = v.getParentNode().flatMap(Node::getParentNode).orElse(null);
        if (gp instanceof ForStmt || gp instanceof ForEachStmt) {
            return false;
        }
        for (Node cur = v.getParentNode().orElse(null); cur != null && cur != md; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof com.github.javaparser.ast.expr.LambdaExpr
                    || cur instanceof com.github.javaparser.ast.body.TypeDeclaration<?>
                    || cur instanceof com.github.javaparser.ast.expr.ObjectCreationExpr o && o.getAnonymousClassBody().isPresent()
                    || cur instanceof com.github.javaparser.ast.body.FieldDeclaration) {
                return false;
            }
        }
        return true;
    }

    /** 식 안에서 일어나는 호출 */
    private void calls(VariableChange c, MethodInfo m) {
        for (CallSiteInfo s : r.callSitesOf(m.id)) {
            if (contains(c.location, s.location)) {
                c.callSiteIds.add(s.id);
            }
        }
    }

    private VariableChange base(VariableInfo v, MethodInfo m, Node node) {
        VariableChange c = new VariableChange();
        c.id = r.nextId("VC");
        c.variableId = v.id;
        c.methodId = m.id;
        c.location = SourceLocation.of(node, m.location.file());
        c.controlBlockId = innermostBlock(m.id, c.location);
        c.conditions.addAll(ConditionText.of(node));
        return c;
    }

    private void add(VariableInfo v, VariableChange c) {
        c.order = v.changeIds.size() + 1;
        r.index(c.id, c);
        r.variableChanges.add(c);
        v.changeIds.add(c.id);
    }

    /** 값 식과 출처. 식 안의 변수 자기 자신(total = total - x 의 total)은 출처에서 제외 */
    private void value(VariableChange c, Expression value, String self, MethodInfo m) {
        c.valueExpression = ConditionText.text(value);
        Map<String, ValueSource> out = new LinkedHashMap<>();
        for (Expression operand : operands(value)) {
            if (operand instanceof NameExpr n && n.getNameAsString().equals(self)) {
                continue;
            }
            for (ValueSource s : tracer.trace(operand, m.id).sources()) {
                out.putIfAbsent(s.display() + s.via(), s);
            }
        }
        c.sources.addAll(out.values());
    }

    private static List<Expression> operands(Expression e) {
        List<Expression> out = new ArrayList<>();
        if (e instanceof EnclosedExpr en) {
            out.addAll(operands(en.getInner()));
        } else if (e instanceof BinaryExpr b) {
            out.addAll(operands(b.getLeft()));
            out.addAll(operands(b.getRight()));
        } else {
            out.add(e);
        }
        return out;
    }

    private static boolean isIncDec(UnaryExpr u) {
        return switch (u.getOperator()) {
            case PREFIX_INCREMENT, POSTFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_DECREMENT -> true;
            default -> false;
        };
    }

    /** for (…; …; update) 의 update / init 안 */
    private static boolean inForHeader(Expression e) {
        Node child = e;
        for (Node cur = e.getParentNode().orElse(null); cur != null; cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof ForStmt f) {
                Node c0 = child;
                return f.getUpdate().stream().anyMatch(u -> u == c0) || f.getInitialization().stream().anyMatch(u -> u == c0);
            }
            if (!(cur instanceof Expression)) {
                return false;
            }
            child = cur;
        }
        return false;
    }

    private String innermostBlock(String methodId, SourceLocation loc) {
        ControlBlock best = null;
        for (ControlBlock b : r.blocksOf(methodId)) {
            if (contains(b.location, loc) && (best == null || contains(best.location, b.location))) {
                best = b;
            }
        }
        return best == null ? null : best.id;
    }

    private static boolean contains(SourceLocation outer, SourceLocation inner) {
        if (outer == null || inner == null || !Objects.equals(outer.file(), inner.file())) {
            return false;
        }
        boolean begin = outer.beginLine() < inner.beginLine()
                || outer.beginLine() == inner.beginLine() && outer.beginColumn() <= inner.beginColumn();
        boolean end = inner.endLine() < outer.endLine()
                || inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn();
        return begin && end;
    }
}
