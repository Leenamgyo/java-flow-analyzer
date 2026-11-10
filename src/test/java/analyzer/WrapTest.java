package analyzer;

import analyzer.mermaid.MermaidGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 긴 코드 줄 나누기: 쉼표 뒤 / 연산자 앞 / ).체인 앞 / ( 뒤, 이어지는 줄에 표시 없음 (예전 "> " 제거 — 사용자 요구) */
class WrapTest {

    @Test
    void shortLineIsUntouched() {
        assertEquals(List.of("#1 String a = b"), MermaidGraph.wrap("#1 String a = b", 100));
        assertEquals(List.of("x".repeat(300)), MermaidGraph.wrap("x".repeat(300), 0)); // 0 = 끊지 않음
    }

    @Test
    void breaksAfterCommaWithContinuationMarker() {
        String line = "#30 String newOrderNo = orderService.registerOrder(order, items, delivery, payment)";
        List<String> out = MermaidGraph.wrap(line, 60);
        assertEquals(List.of("#30 String newOrderNo = orderService.registerOrder(order,", "items, delivery, payment)"), out);
        assertEquals(line.replace(" ", ""), String.join("", out).replace(" ", ""));
    }

    @Test
    void breaksBeforeMethodChainAndOperator() {
        List<String> chain = MermaidGraph.wrap("result = OrderResultDTO.builder().orderNo(newOrderNo).totalAmount(total).build()", 40);
        assertTrue(chain.stream().skip(1).allMatch(l -> l.startsWith(".")), chain.toString()); // '(' 뒤보다 ).체인 앞 우선
        List<String> op = MermaidGraph.wrap("long total = calculateTotal(items) - couponService.discount(memberId, code, x)", 45);
        assertTrue(op.stream().noneMatch(l -> l.startsWith("> ")), op.toString());
        assertTrue(op.stream().allMatch(l -> l.length() <= 60), op.toString());
    }

    @Test
    void doesNotBreakInsideStringLiteralWhenItFits() {
        List<String> out = MermaidGraph.wrap("foo.bar(x, \"a, b, c, d, e, f, g, h, i, j, k\")", 40);
        assertEquals(List.of("foo.bar(x,", "\"a, b, c, d, e, f, g, h, i, j, k\")"), out);
    }

    /** 방어: 끊기 좋은 자리가 없는 긴 덩어리(문자열 리터럴·식별자)도 한도를 크게 넘지 않는다 */
    @Test
    void longLiteralAndTokenAreBoundedWithoutLosingText() {
        String sql = "#3 log.debug(\"" + "SELECT A.ID, A.NAME FROM TB_APP A WHERE A.USE_YN = 'Y' ".repeat(8) + "\")";
        assertBounded(sql, 80);
        String token = "#4 x = " + "abcdefghij".repeat(40);
        assertBounded(token, 80);
        // 리터럴 안이라도 공백이 있으면 공백에서 끊는다 (단어 중간 자르기는 최후 수단)
        assertTrue(MermaidGraph.wrap(sql, 80).stream().skip(1).noneMatch(l -> l.matches("> [A-Z]{1,3}[a-z].*")));
    }

    /** 한글은 화면에서 2칸 — 글자 수가 아니라 폭으로 잰다 */
    @Test
    void wideCharactersCountDouble() {
        assertEquals(20, MermaidGraph.width("가나다라마바사아자차"));
        String msg = "#5 log.info(\"" + "주문 등록 처리 중 오류가 발생했습니다 ".repeat(10) + "\")";
        assertBounded(msg, 80);
    }

    @Test
    void subgraphTitleAndEdgeLabelAreWrapped() {
        MermaidGraph g = new MermaidGraph().maxLineLength(40);
        MermaidGraph.Subgraph sg = g.subgraph(g.root(), "sg_M1", "VeryLongClassNameForTesting.veryLongMethodNameForTesting() «Action»");
        g.node(sg, "A", List.of("a"), MermaidGraph.Shape.RECT, null);
        g.node(sg, "B", List.of("b"), MermaidGraph.Shape.RECT, null);
        g.edge("A", "B", "VeryLongExceptionNameOne | VeryLongExceptionNameTwo | Three", MermaidGraph.EdgeStyle.EXCEPTION);
        String out = g.render("TD", java.util.Map.of());
        String title = out.lines().filter(l -> l.contains("subgraph sg_M1")).findFirst().orElseThrow();
        String edge = out.lines().filter(l -> l.contains("-.->")).findFirst().orElseThrow();
        assertTrue(title.contains("<br/>"), title);
        assertTrue(edge.contains("<br/>"), edge);
    }

    private static void assertBounded(String line, int max) {
        List<String> out = MermaidGraph.wrap(line, max);
        assertTrue(out.size() > 1, out.toString());
        for (String l : out) {
            assertTrue(MermaidGraph.width(l) <= max + max / 3, MermaidGraph.width(l) + " > " + max + ": " + l);
        }
        String joined = String.join("", out);
        assertEquals(line.replace(" ", ""), joined.replace(" ", ""));
    }
}
