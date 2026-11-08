package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.VariableChange;
import analyzer.model.VariableInfo;
import analyzer.phase.Phase;
import analyzer.phase.PhaseAnalyzer;
import analyzer.phase.PhaseModel;
import analyzer.serializer.CallTreeWriter;
import analyzer.serializer.MappingReportWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 변수 상태 변화 (V* / VC*) — 수집 규칙과 흐름도·트리·mapping·단계 보기 표현 */
class VariableStateTest {

    static List<VariableChange> history(AnalysisResult r, VariableInfo v) {
        return v.changeIds.stream().map(r::variableChange).toList();
    }

    static VariableInfo var(AnalysisResult r, String method, String name) {
        return r.variables.stream().filter(v -> r.method(v.methodId).name.equals(method) && v.name.equals(name))
                .findFirst().orElse(null);
    }

    @Test
    void collectsOnlyLocalStateChanges() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "variableState");
        VariableInfo size = var(r, "variableState", "size");
        assertNotNull(size);
        List<VariableChange> h = history(r, size);
        assertEquals(List.of("INIT", "ASSIGN"), h.stream().map(c -> c.op).toList());
        assertEquals("request.size", h.get(0).sources.get(0).display());
        assertEquals(List.of("size > 100"), h.get(1).conditions);
        assertEquals("IF", r.block(h.get(1).controlBlockId).type.idPrefix());

        VariableInfo total = var(r, "variableState", "total");
        assertEquals(List.of("INIT", "COMPOUND", "INCREMENT"), history(r, total).stream().map(c -> c.op).toList());
        assertEquals("+=", history(r, total).get(1).operator);
        assertTrue(history(r, total).get(1).conditions.get(0).startsWith("loop: "));

        VariableInfo limit = var(r, "variableState", "limit");
        assertEquals("PARAMETER", limit.kind);
        assertEquals(List.of("PARAM", "ASSIGN"), history(r, limit).stream().map(c -> c.op).toList());

        VariableInfo label = var(r, "variableState", "label");
        VariableChange assign = history(r, label).get(1);
        assertNull(history(r, label).get(0).valueExpression); // 선언만
        assertEquals(1, assign.callSiteIds.size());

        // 제외: for 헤더 변수, 향상된 for 변수, 필드, 변경 없는 변수
        for (String name : List.of("i", "item", "counter", "once", "vo")) {
            assertNull(var(r, "variableState", name), name);
        }
    }

    @Test
    void flowchartTreeAndMappingShowChanges() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "variableState");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        VariableInfo size = var(r, "variableState", "size");
        VariableChange clamp = history(r, size).get(1);
        // 상태 변화 노드: 조건 분기 안, 변경만 센 순번
        assertTrue(mmd.contains("VAR_" + clamp.id + "[\"상태 변화<br/>size = 100 · " + size.id + " 1/1\"]"), mmd);
        // x = call(): 별도 노드 없이 호출 노드에 "→ x"
        VariableInfo label = var(r, "variableState", "label");
        VariableChange assign = history(r, label).get(1);
        String callId = assign.callSiteIds.get(0);
        assertEquals(callId, g.nodeOf(assign.id));
        // 타입 없는 대입 = 값 변경 (자바 코드 그대로 타입을 붙이지 않음)
        assertTrue(mmd.contains("[\"#35;4 label = format(once) · " + label.id + " 1/1\"]"), mmd);
        // 선언(타입 있는 초기화)은 값 변경 노드로 그리지 않는다
        assertFalse(mmd.contains("값 변경<br/>int size"), mmd);
        assertFalse(mmd.contains("값 변경<br/>long total = 0"), mmd);
        // 값이 바뀌는 변수가 VO 로 → ⟳
        assertTrue(mmd.contains("page = size  ⟳" + size.id), mmd);

        String tree = new CallTreeWriter(true).render(r);
        assertTrue(tree.contains("≔ size = 100 · " + size.id + " 1/1  (" + clamp.id + ")"), tree);
        String mapping = new MappingReportWriter().render(r);
        assertTrue(mapping.contains("## 변수 상태 변화"));
        assertTrue(mapping.contains("| " + clamp.id + " | 대입 = | `size = 100` | literal:100 | `size > 100` |"), mapping);
    }

    @Test
    void phaseViewDrawsChangeOnStatementWithLogicCall() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "stateWithLogic",
                c -> c.analysis.expandRoles = List.of("Biz"));
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        VariableInfo count = var(r, "stateWithLogic", "count");
        // 기존: "변수 변경 노드 누락" 으로 render 실패
        String mmd = new FlowchartGenerator(pm).render(r, "TD");
        MermaidGraph g = new FlowchartGenerator(pm).build(r);
        for (VariableChange c : history(r, count)) {
            if (c.isChange()) {
                assertTrue(g.has(c.id), c.id + " " + c.expression);
            }
        }
        assertTrue(mmd.contains("count = count + noticeBiz.list(#quot;a#quot;).size() · " + count.id + " 1/2"), mmd);
        // 일반 흐름도도 동일
        new FlowchartGenerator().render(r, "TD");
    }

    @Test
    void phaseViewDrawsChangeAtTopOfTryWithoutCalls() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "stateInTry", c -> c.analysis.expandRoles = List.of("Biz"));
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        String mmd = new FlowchartGenerator(pm).render(r, "TD"); // 기존: "변수 변경 노드 누락"
        VariableInfo retry = var(r, "stateInTry", "retry");
        assertTrue(mmd.contains("retry++ · " + retry.id + " 1/1"), mmd);
        new FlowchartGenerator().render(r, "TD");
    }

    @Test
    void exampleTotalHistoryAndPhase() throws Exception {
        AnalyzerConfig cfg = PhaseTest.example("OrderRegisterAction");
        AnalysisResult r = Main.analyze(cfg);
        VariableInfo total = var(r, "execute", "total");
        List<VariableChange> h = history(r, total);
        assertEquals(3, h.size());
        assertEquals("total -= usePoint", h.get(2).expression);
        assertEquals("request.usePoint", h.get(2).sources.get(0).display());
        assertEquals(List.of("usePoint > 0"), h.get(2).conditions);

        cfg.target.className = "OrderListAction";
        AnalysisResult list = Main.analyze(cfg);
        PhaseModel pm = PhaseAnalyzer.analyze(list, cfg.phases);
        PhaseModel.Step clamp = pm.steps.stream().filter(s -> s.element instanceof ControlBlock b
                && "size > Constants.MAX_PAGE_SIZE".equals(b.condition)).findFirst().orElseThrow();
        assertEquals(Phase.BINDING, clamp.phase);
        assertTrue(clamp.reason.contains("요청 값 보정"), clamp.reason);
        assertEquals(1, clamp.changes.size());
        // switch 의 각 case 에서 orders = service 호출 → 호출 노드에 "→ orders"
        String mmd = new FlowchartGenerator(pm).render(list, "TD");
        assertTrue(mmd.contains("\"#35;13 orders = orderService.findOrders(cond, page, size) · V"), mmd); // 대입이라 타입 없음
        // 레이어: Service 는 Service·Biz 레이어 안, Service·DAO 호출 없는 보조 메서드는 세부 생략
        assertTrue(mmd.contains("subgraph sg_LAYER_SERVICE[\"Service · Biz 레이어\"]"), mmd);
        assertTrue(mmd.contains("subgraph sg_LAYER_ACTION[\"Action 레이어\"]"), mmd);
        CallSiteInfo find = list.callSites.stream().filter(s -> s.methodName.equals("findOrders")).findFirst().orElseThrow();
        assertTrue(mmd.contains(find.id + "[\""));
    }
}
