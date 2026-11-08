package analyzer.binding;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 노드가 실행되기 위한 조건 (바깥 → 안쪽). 메서드 경계에서 멈춘다.
 * if 의 then → 조건식, else → !(조건식), switch → selector == label, 반복문 → loop: 헤더, catch → catch 타입, 람다 → deferred.
 */
final class ConditionText {
    private ConditionText() {
    }

    static List<String> of(Node node) {
        List<String> out = new ArrayList<>();
        Node child = node;
        Node cur = node.getParentNode().orElse(null);
        while (cur != null && !(cur instanceof CallableDeclaration<?>)) {
            final Node c0 = child;
            if (cur instanceof IfStmt i) {
                if (i.getThenStmt() == child) {
                    out.add(text(i.getCondition()));
                } else if (i.getElseStmt().filter(e -> e == c0).isPresent()) {
                    out.add("!(" + text(i.getCondition()) + ")");
                }
            } else if (cur instanceof SwitchEntry e && e.getStatements().contains(child)) {
                String selector = cur.getParentNode().filter(SwitchStmt.class::isInstance)
                        .map(p -> text(((SwitchStmt) p).getSelector())).orElse("switch");
                out.add(e.getLabels().isEmpty() ? selector + ": default"
                        : selector + " == " + String.join(" | ", e.getLabels().stream().map(ConditionText::text).toList()));
            } else if (cur instanceof ForStmt f && f.getBody() == child) {
                out.add("loop: for (" + f.getCompare().map(ConditionText::text).orElse("") + ")");
            } else if (cur instanceof ForEachStmt f && f.getBody() == child) {
                out.add("loop: for (" + text(f.getVariable()) + " : " + text(f.getIterable()) + ")");
            } else if (cur instanceof WhileStmt w && w.getBody() == child) {
                out.add("loop: while (" + text(w.getCondition()) + ")");
            } else if (cur instanceof DoStmt d && d.getBody() == child) {
                out.add("loop: do-while (" + text(d.getCondition()) + ")");
            } else if (cur instanceof CatchClause c && c.getBody() == child) {
                out.add("catch " + c.getParameter().getType().asString());
            } else if (cur instanceof LambdaExpr) {
                out.add("deferred(lambda)");
            } else if (cur instanceof ConditionalExpr c && c.getCondition() != child) {
                out.add(c.getThenExpr() == child ? text(c.getCondition()) : "!(" + text(c.getCondition()) + ")");
            } else if (cur instanceof BinaryExpr b && b.getRight() == child) {
                if (b.getOperator() == BinaryExpr.Operator.AND) {
                    out.add(text(b.getLeft()));
                } else if (b.getOperator() == BinaryExpr.Operator.OR) {
                    out.add("!(" + text(b.getLeft()) + ")");
                }
            }
            child = cur;
            cur = cur.getParentNode().orElse(null);
        }
        Collections.reverse(out);
        return out;
    }

    static String text(Node n) {
        return analyzer.parser.Code.text(n); // 주석 제외, 줄이지 않음
    }
}
