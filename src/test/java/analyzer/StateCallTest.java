package analyzer;

import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 상태 변화(다시 대입)가 메서드 호출과 엮일 때 같은 문장이 노드로 하나 더 생기던 문제 (사용자 보고) —
 * 문장을 보이는 호출 노드 줄에 버전 꼬리표(글자)만 붙이고 '값 변경' 노드는 만들지 않는다. fixture EdgeAction.stateWithCalls
 */
class StateCallTest {

    @ParameterizedTest
    @ValueSource(strings = {"default", "external"})
    void stateChangeIsTextOnTheCallNode(String variant) {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "stateWithCalls", FlowchartSweepTest.VARIANTS.get(variant));
        MermaidGraph g = new FlowchartGenerator().build(r);
        List<String> lines = new ArrayList<>();
        for (String id : g.nodeIds()) {
            g.nodeById(id).lines().forEach(l -> lines.add(MermaidGraph.plain(l)));
        }
        // 문장마다 한 번 (호출 줄 + 꼬리표)
        assertEquals(1, lines.stream().filter(l -> l.contains("userId = normalizeId(userId) + \"_x\"")).count(), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.matches("#\\d+ userId = normalizeId\\(userId\\) \\+ \"_x\" · userId v3")), lines.toString());
        assertEquals(1, lines.stream().filter(l -> l.contains("normalizeId(userId).length()")).count(), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.matches("#\\d+ count \\+= normalizeId\\(userId\\)\\.length\\(\\) · count v2")), lines.toString());
        // 복합 대입을 = 로 바꿔 쓰지 않는다
        assertFalse(lines.stream().anyMatch(l -> l.contains("count = normalizeId")), lines.toString());
        // 호출 결과를 그대로 받는 대입은 예전처럼
        assertTrue(lines.stream().anyMatch(l -> l.matches("#\\d+ userId = normalizeId\\(userId\\) · userId v2")), lines.toString());
        // 값 변경 노드가 따로 없다 (모든 변경이 호출 줄에 흡수)
        assertFalse(g.nodeIds().stream().anyMatch(id -> id.startsWith("VAR_") && g.nodeById(id).lines().stream()
                .map(MermaidGraph::plain).anyMatch(l -> l.contains("normalizeId"))), lines.toString());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
    }

    /** 요청 읽기가 세부 호출 인자 안에 있으면(String k = middle(request.getParameter("k"))) 문장은 한 번 — 세부 호출은 호출식만 */
    @ParameterizedTest
    @ValueSource(strings = {"default", "external", "deep", "flat"})
    void readInsideDetailCallIsNotDrawnTwice(String variant) {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "inlineRepeats", FlowchartSweepTest.VARIANTS.get(variant));
        MermaidGraph g = new FlowchartGenerator().build(r);
        List<String> lines = new ArrayList<>();
        for (String id : g.nodeIds()) {
            g.nodeById(id).lines().forEach(l -> lines.add(MermaidGraph.plain(l)));
        }
        assertEquals(1, lines.stream().filter(l -> l.contains("String k = middle(request.k)")).count(), lines.toString());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
    }
}
