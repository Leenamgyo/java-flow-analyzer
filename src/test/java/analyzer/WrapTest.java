package analyzer;

import analyzer.mermaid.MermaidGraph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 긴 코드 줄 나누기: 쉼표 뒤 / 연산자 앞 / ).체인 앞 / ( 뒤, 이어지는 줄은 "> " */
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
        assertEquals(List.of("#30 String newOrderNo = orderService.registerOrder(order,", "> items, delivery, payment)"), out);
        assertEquals(line.replace(" ", ""), String.join("", out).replace("> ", "").replace(" ", ""));
    }

    @Test
    void breaksBeforeMethodChainAndOperator() {
        List<String> chain = MermaidGraph.wrap("result = OrderResultDTO.builder().orderNo(newOrderNo).totalAmount(total).build()", 40);
        assertTrue(chain.stream().skip(1).allMatch(l -> l.startsWith("> .")), chain.toString()); // '(' 뒤보다 ).체인 앞 우선
        List<String> op = MermaidGraph.wrap("long total = calculateTotal(items) - couponService.discount(memberId, code, x)", 45);
        assertTrue(op.stream().skip(1).allMatch(l -> l.startsWith("> ")), op.toString());
        assertTrue(op.stream().allMatch(l -> l.length() <= 60), op.toString());
    }

    @Test
    void doesNotBreakInsideStringLiteral() {
        List<String> out = MermaidGraph.wrap("log(\"a, b, c, d, e, f, g, h, i, j, k\")", 20);
        assertTrue(out.stream().noneMatch(l -> l.equals("> b, c, d, e, f, g, h, i, j, k\")")), out.toString());
        assertTrue(String.join("", out).contains("\"a, b, c, d, e, f, g, h, i, j, k\""), out.toString());
    }
}
