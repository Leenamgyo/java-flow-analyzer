package analyzer.binding;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.ValueSource;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 값 표현식 → 최초 출처 + 변환 추적 (보수적, 추측하지 않음).
 * <pre>
 * String v = request.getParameter("startNum");  int start = Integer.parseInt(v);  vo.setStartNum(start);
 *   → sources: [request.startNum], transforms: [Integer.parseInt]
 * </pre>
 * 규칙: Request 읽기 → REQUEST / 리터럴 → LITERAL / static final 상수 → CONSTANT /
 * 지역 변수 → 초기화식·대입식들(2개 이상이면 reassigned) / 메서드 파라미터 → 분석된 호출 위치의 같은 인자(via), 호출자가 없으면 PARAMETER /
 * getter 호출 → CALL(반환값 내부는 추적하지 않음) / static·같은 클래스 메서드 → 변환 + 인자 추적 / 인스턴스 메서드 → 변환 + 수신 객체 추적 /
 * 그 외 → UNRESOLVED.
 */
final class SourceTracer {
    private static final int MAX_HOPS = 8;

    record Result(List<ValueSource> sources, List<String> transforms, boolean reassigned) {
    }

    private final AnalysisResult r;
    private final Map<String, MethodDeclaration> declarations;
    private final Map<MethodCallExpr, List<RequestParameterInfo>> paramsByCall;
    private final Map<String, MethodCallExpr> callExprs;

    SourceTracer(AnalysisResult r, Map<String, MethodDeclaration> declarations,
                 Map<MethodCallExpr, List<RequestParameterInfo>> paramsByCall, Map<String, MethodCallExpr> callExprs) {
        this.r = r;
        this.declarations = declarations;
        this.paramsByCall = paramsByCall;
        this.callExprs = callExprs;
    }

    private final class Acc {
        final Map<String, ValueSource> sources = new LinkedHashMap<>();
        final Set<String> transforms = new LinkedHashSet<>();
        final Set<String> visited = new HashSet<>();
        boolean reassigned;

        void add(ValueSource s) {
            sources.putIfAbsent(s.kind() + "|" + s.value() + "|" + s.via(), s);
        }
    }

    Result trace(Expression e, String methodId) {
        Acc acc = new Acc();
        trace(e, methodId, List.of(), true, false, 0, acc);
        return new Result(new ArrayList<>(acc.sources.values()), new ArrayList<>(acc.transforms), acc.reassigned);
    }

    /**
     * @param literalIsSource 리터럴을 출처로 볼지 (값 전체이거나 삼항 분기일 때만. 연산식 안의 리터럴은 가공의 일부)
     * @param inExpr          이미 연산식 변환을 기록함 (중첩 연산식은 다시 기록하지 않음)
     */
    private void trace(Expression e, String methodId, List<String> via, boolean literalIsSource, boolean inExpr,
                       int hops, Acc acc) {
        if (hops > MAX_HOPS || !acc.visited.add(methodId + "@" + e.getRange().map(Object::toString).orElse(e.toString()))) {
            return;
        }
        while (e instanceof EnclosedExpr en) {
            e = en.getInner();
        }
        MethodDeclaration md = declarations.get(methodId);
        if (e instanceof CastExpr c) {
            acc.transforms.add("(" + c.getType().asString() + ")");
            trace(c.getExpression(), methodId, via, literalIsSource, inExpr, hops, acc);
        } else if (e instanceof NullLiteralExpr) {
            if (literalIsSource) {
                acc.add(new ValueSource("LITERAL", "null", null, via));
            }
        } else if (e instanceof LiteralExpr l) {
            if (literalIsSource) {
                acc.add(new ValueSource("LITERAL", l instanceof StringLiteralExpr s ? s.asString() : l.toString(), null, via));
            }
        } else if (e instanceof MethodCallExpr mc) {
            call(mc, methodId, via, inExpr, hops, acc);
        } else if (e instanceof NameExpr n && md != null) {
            name(n, md, methodId, via, literalIsSource, inExpr, hops, acc);
        } else if (e instanceof FieldAccessExpr f) {
            if (f.getScope() instanceof ThisExpr) {
                acc.add(new ValueSource("FIELD", f.getNameAsString(), null, via));
            } else if (f.getScope() instanceof NameExpr s && Character.isUpperCase(s.getNameAsString().charAt(0))) {
                acc.add(new ValueSource("CONSTANT", ConditionText.text(f), null, via));
            } else {
                acc.add(new ValueSource("FIELD", ConditionText.text(f), null, via));
            }
        } else if (e instanceof ArrayAccessExpr a) {
            acc.transforms.add("[" + ConditionText.text(a.getIndex()) + "]");
            trace(a.getName(), methodId, via, false, inExpr, hops, acc);
        } else if (e instanceof BinaryExpr b) {
            if (!inExpr) {
                acc.transforms.add("expr: " + ConditionText.text(b));
            }
            trace(b.getLeft(), methodId, via, false, true, hops, acc);
            trace(b.getRight(), methodId, via, false, true, hops, acc);
        } else if (e instanceof UnaryExpr u) {
            if (!inExpr) {
                acc.transforms.add("expr: " + ConditionText.text(u));
            }
            trace(u.getExpression(), methodId, via, false, true, hops, acc);
        } else if (e instanceof ConditionalExpr c) {
            acc.transforms.add("조건식: " + ConditionText.text(c));
            trace(c.getThenExpr(), methodId, via, true, inExpr, hops, acc);
            trace(c.getElseExpr(), methodId, via, true, inExpr, hops, acc);
        } else if (e instanceof ObjectCreationExpr o) {
            acc.add(new ValueSource("NEW", o.getType().asString(), null, via));
        } else {
            acc.add(new ValueSource("UNRESOLVED", ConditionText.text(e), null, via));
        }
    }

    /** 값을 그대로 변환하는 것이 명백한 static 호출의 수신 타입 (JDK 변환/래퍼) */
    private static final Set<String> CONVERSION_TYPES = Set.of("Integer", "Long", "Double", "Float", "Short", "Byte",
            "Boolean", "String", "BigDecimal", "BigInteger", "Objects", "Math", "LocalDate", "LocalDateTime");
    /** 값을 가공하는 인스턴스 메서드 (수신 객체가 원본 값) */
    private static final Set<String> VALUE_METHODS = Set.of("trim", "strip", "toUpperCase", "toLowerCase", "substring",
            "replace", "replaceAll", "concat", "split", "toString", "intValue", "longValue", "doubleValue", "toCharArray",
            "equals", "equalsIgnoreCase", "length", "isEmpty");

    private void call(MethodCallExpr mc, String methodId, List<String> via, boolean inExpr, int hops, Acc acc) {
        List<RequestParameterInfo> ps = paramsByCall.getOrDefault(mc, List.of());
        if (!ps.isEmpty()) {
            for (RequestParameterInfo p : ps) {
                acc.add(new ValueSource("REQUEST", requestName(p), p.id, via));
            }
            return;
        }
        String name = mc.getNameAsString();
        var scope = mc.getScope();
        String scopeType = scope.filter(x -> x instanceof NameExpr n && Character.isUpperCase(n.getNameAsString().charAt(0)))
                .map(Object::toString).orElse(null);
        String label = scope.map(x -> x + ".").orElse("") + name + "()";
        if (mc.getArguments().isEmpty() && (scope.isEmpty() || scopeType != null)) {
            acc.add(new ValueSource("CALL", ConditionText.text(mc), null, via)); // 인자 없는 static/같은 클래스 호출 = 값의 출처
            return;
        }
        if (scopeType != null && (CONVERSION_TYPES.contains(scopeType) || scopeType.endsWith("Util") || scopeType.endsWith("Utils"))) {
            acc.transforms.add(label); // Integer.parseInt, StringUtil.trimToEmpty …: 인자가 원본
            mc.getArguments().forEach(a -> trace(a, methodId, via, false, inExpr, hops, acc));
            return;
        }
        if (scope.isPresent() && scopeType == null && VALUE_METHODS.contains(name)) {
            acc.transforms.add("." + name + "()"); // value.trim(): 수신 객체가 원본
            trace(scope.get(), methodId, via, false, inExpr, hops, acc);
            return;
        }
        if (scope.isEmpty() || scopeType != null) {
            // 같은 클래스/다른 source 클래스 static 메서드: 인자가 요청 값이면 그 값을 가공하는 것으로 보고, 아니면 반환값을 출처로 둔다
            Acc tmp = new Acc();
            tmp.visited.addAll(acc.visited);
            mc.getArguments().forEach(a -> trace(a, methodId, via, false, inExpr, hops, tmp));
            if (tmp.sources.values().stream().anyMatch(v -> v.kind().equals("REQUEST"))) {
                acc.transforms.add(label);
                acc.transforms.addAll(tmp.transforms);
                tmp.sources.values().forEach(acc::add);
                acc.reassigned |= tmp.reassigned;
                return;
            }
        }
        acc.add(new ValueSource("CALL", ConditionText.text(mc), null, via)); // getter·서비스 호출 등: 반환값 내부는 추적하지 않음
    }

    private void name(NameExpr n, MethodDeclaration md, String methodId, List<String> via, boolean literalIsSource,
                      boolean inExpr, int hops, Acc acc) {
        String name = n.getNameAsString();
        List<RequestParameterInfo> byVar = r.parameters.stream()
                .filter(p -> methodId.equals(p.sourceMethodId) && name.equals(p.variable)).toList();
        if (!byVar.isEmpty()) {
            for (RequestParameterInfo p : byVar) {
                acc.add(new ValueSource("REQUEST", requestName(p), p.id, via));
                if (assignments(md, name).size() > (p.callSiteId == null ? 0 : 1)) {
                    acc.reassigned = true;
                    for (Expression v : assignments(md, name)) {
                        if (!(v instanceof MethodCallExpr mc && paramsByCall.getOrDefault(mc, List.of()).contains(p))) {
                            trace(v, methodId, via, true, inExpr, hops + 1, acc);
                        }
                    }
                }
            }
            return;
        }
        for (int i = 0; i < md.getParameters().size(); i++) {
            if (!md.getParameter(i).getNameAsString().equals(name)) {
                continue;
            }
            List<Expression> own = assignments(md, name);
            if (!own.isEmpty()) {
                acc.reassigned = true;
                own.forEach(v -> trace(v, methodId, via, true, inExpr, hops + 1, acc));
            }
            boolean any = false;
            for (CallSiteInfo s : r.callSites) {
                if (!(methodId.equals(s.targetMethodId) || methodId.equals(s.implementationMethodId))) {
                    continue;
                }
                MethodCallExpr call = callExprs.get(s.id);
                if (call == null || call.getArguments().size() <= i) {
                    continue;
                }
                any = true;
                List<String> v = new ArrayList<>();
                v.add(s.id);
                v.addAll(via);
                trace(call.getArgument(i), s.callerMethodId, v, literalIsSource, inExpr, hops + 1, acc);
            }
            if (!any) {
                acc.add(new ValueSource("PARAMETER", name, null, via));
            }
            return;
        }
        for (com.github.javaparser.ast.stmt.ForEachStmt f : md.findAll(com.github.javaparser.ast.stmt.ForEachStmt.class)) {
            if (f.getVariable().getVariables().stream().anyMatch(v -> v.getNameAsString().equals(name))) {
                acc.add(new ValueSource("LOOP", name + " : " + ConditionText.text(f.getIterable()), null, via));
                return;
            }
        }
        for (com.github.javaparser.ast.stmt.ForStmt f : md.findAll(com.github.javaparser.ast.stmt.ForStmt.class)) {
            boolean counter = f.getInitialization().stream().anyMatch(i -> i instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr v
                    && v.getVariables().stream().anyMatch(d -> d.getNameAsString().equals(name)));
            if (counter) {
                acc.add(new ValueSource("LOOP", name, null, via)); // 반복 변수: 초기값은 출처가 아님
                return;
            }
        }
        List<Expression> values = assignments(md, name);
        if (!values.isEmpty()) {
            if (values.size() > 1) {
                acc.reassigned = true;
            }
            values.forEach(v -> trace(v, methodId, via, true, inExpr, hops + 1, acc));
            return;
        }
        boolean local = !md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name)).isEmpty();
        if (!local) {
            TypeDeclaration<?> td = md.findAncestor(TypeDeclaration.class).map(t -> (TypeDeclaration<?>) t).orElse(null);
            if (td != null && td.getFieldByName(name).isPresent()) {
                FieldDeclaration f = td.getFieldByName(name).get();
                acc.add(new ValueSource(f.isStatic() && f.isFinal() ? "CONSTANT" : "FIELD", name, null, via));
                return;
            }
        }
        acc.add(new ValueSource("UNRESOLVED", name, null, via));
    }

    private static String requestName(RequestParameterInfo p) {
        if ("UNKNOWN".equals(p.nameResolution)) {
            return "?(" + p.nameExpression + ")";
        }
        return p.name == null ? "*" : p.name;
    }

    /** 지역 변수/파라미터에 들어가는 값: 선언 초기화식 + 단순 대입(=) 우변. 복합 대입(+= 등)도 값으로 포함 */
    private static List<Expression> assignments(MethodDeclaration md, String name) {
        List<Node> nodes = new ArrayList<>();
        md.findAll(VariableDeclarator.class, v -> v.getNameAsString().equals(name) && v.getInitializer().isPresent())
                .forEach(nodes::add);
        md.findAll(AssignExpr.class, a -> a.getTarget() instanceof NameExpr t && t.getNameAsString().equals(name))
                .forEach(nodes::add);
        nodes.sort((a, b) -> a.getBegin().flatMap(x -> b.getBegin().map(x::compareTo)).orElse(0));
        List<Expression> out = new ArrayList<>();
        for (Node x : nodes) {
            out.add(x instanceof VariableDeclarator v ? v.getInitializer().get() : ((AssignExpr) x).getValue());
        }
        return out;
    }
}
