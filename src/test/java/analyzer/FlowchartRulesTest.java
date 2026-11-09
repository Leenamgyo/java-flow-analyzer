package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 흐름도 규칙: 상태 변화 · 로그 · 바인딩 · Loop + 빠진 코드 없음(exclude 는 사용자 설정) — fixture CaseAction.flowRules */
class FlowchartRulesTest {

    static final String REQ = "<span style='color:" + MermaidGraph.TEXT_COLORS.get("req") + "'>";
    static final String FN = "<span style='color:" + MermaidGraph.TEXT_COLORS.get("fn") + "'>";

    static CallSiteInfo call(AnalysisResult r, String expression) {
        return r.callSites.stream().filter(c -> c.expression.equals(expression)).findFirst()
                .orElseThrow(() -> new AssertionError("call not found: " + expression));
    }

    static ControlBlock block(AnalysisResult r, analyzer.model.BlockType t) {
        return r.blocks.stream().filter(b -> b.type == t).findFirst().orElseThrow();
    }

    @Test
    void voidCallsAreNotStateChanges() {
        // void 라고 상태를 바꾼다는 보장이 없다 — release(); publish(vo); release(); 는 각자 호출 노드 (==> 메서드)
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        CallSiteInfo first = call(r, "release()");
        CallSiteInfo publish = call(r, "publish(vo)");
        assertFalse(first.id.equals(g.nodeOf(publish.id)));
        assertTrue(mmd.contains(first.id + "[\"#35;5 release()\"]"), mmd);
        assertTrue(mmd.contains(publish.id + "[\"#35;6 publish(vo)\"]"), mmd);
        assertFalse(mmd.contains("상태 변화<br/>"), mmd); // 이 메서드에는 변수 값 변경이 없다
        assertTrue(g.edges().stream().anyMatch(e -> e.from().equals(publish.id) && e.to().equals(publish.targetMethodId)
                && e.style() == MermaidGraph.EdgeStyle.CALL), g.edges().toString());
    }

    @Test
    void loggingIsOneBlockWithItsOwnBackground() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        // include-external-library-calls: false 여도 로깅 호출은 모델에 남는다
        CallSiteInfo info = call(r, "LOG.info(\"start \" + request.getRequestURI())");
        CallSiteInfo debug = call(r, "LOG.debug(\"items={}\", items.size())");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(info.id, g.nodeOf(debug.id));
        String mmd = new FlowchartGenerator().render(r, "TD");
        // 간략히: 레벨 + 인자 (수신 객체·세미콜론 없음)
        assertTrue(mmd.contains(info.id + "[\"로그<br/>#35;1 info #quot;start #quot; + request.getRequestURI()<br/>"
                + "#35;2 debug #quot;items={}#quot;, items.size()\"]"), mmd);
        assertTrue(mmd.contains("classDef log fill:"), mmd);
        assertTrue(mmd.matches("(?s).*class [^\\n]*" + info.id + "[^\\n]* log\\n.*"), mmd);
    }

    @Test
    void requestParametersAndNestedBindingFunctionsShareTheBindingBlock() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        MermaidGraph g = new FlowchartGenerator().build(r);
        CallSiteInfo read = call(r, "request.getParameter(\"id\")");
        CallSiteInfo toSearch = call(r, "toSearch(request)");
        // 요청 파라미터는 따로 블록이 아니라 바인딩 블록 안
        assertEquals(read.id, g.nodeOf(toSearch.id));
        String mmd = new FlowchartGenerator().render(r, "TD");
        // 요청 파라미터 = 글자색 req, 바인딩 함수 = 글자색 fn, 안쪽 바인딩 함수는 │ 로 한 단계 더 중첩
        assertTrue(mmd.contains(read.id + "[\"바인딩<br/>" + REQ + "#35;3 String id = request.id</span><br/>"
                + FN + "▸ #35;4 SearchVO vo = toSearch(request)</span><br/>"
                + "│ keyword = trimmed(request.q)<br/>"
                + FN + "│ ▸ trimmed(request.q)</span><br/>"
                + "│ │ return v == null ? #quot;#quot; : v.trim()<br/>"
                + "│ sort = request.sort<br/>"
                + "│ return vo\"]"), mmd);
        // 바인딩 함수는 따로 메서드 상자를 그리지 않는다 (본문은 블록 안에 전부 보임)
        assertFalse(g.has("sg_" + toSearch.targetMethodId));
        assertTrue(FlowchartGenerator.bindingFunctions(r).contains(call(r, "trimmed(request.getParameter(\"q\"))").targetMethodId));
    }

    @Test
    void loopsAreSubgraphsAndKeepTheirFlow() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        ControlBlock w = block(r, analyzer.model.BlockType.WHILE);
        ControlBlock d = block(r, analyzer.model.BlockType.DO_WHILE);
        for (ControlBlock b : List.of(w, d)) {
            assertTrue(mmd.contains("subgraph sg_" + b.id + "[\"Loop\"]"), mmd);
            assertTrue(g.idsUnder("sg_" + b.id).contains(b.id));
        }
        String inWhile = g.nodeOf(r.callSites.stream().filter(c -> w.id.equals(c.controlBlockId)).findFirst().orElseThrow().id);
        String inDo = g.nodeOf(r.callSites.stream().filter(c -> d.id.equals(c.controlBlockId)).findFirst().orElseThrow().id);
        assertTrue(g.idsUnder("sg_" + w.id).contains(inWhile));
        // while: 조건 → loop → 본문 → next → 조건, 조건 → done
        assertTrue(mmd.contains(w.id + " -->|\"loop\"| " + inWhile), mmd);
        String whileLast = g.nodeOf(r.callSites.stream().filter(c -> w.id.equals(c.controlBlockId)).reduce((a, b) -> b).orElseThrow().id);
        assertTrue(mmd.contains(whileLast + " -->|\"next\"| " + w.id), mmd);
        assertTrue(mmd.contains(w.id + " -->|\"done\"| "), mmd);
        // do-while: 본문이 먼저 → 조건 → loop 이면 본문으로
        assertTrue(mmd.contains(w.id + " -->|\"done\"| " + inDo), mmd);
        assertTrue(mmd.contains(inDo + " --> " + d.id), mmd);
        assertTrue(mmd.contains(d.id + " -->|\"loop\"| " + inDo), mmd);
    }

    @Test
    void nothingIsDroppedUnlessTheUserExcludesIt() throws Exception {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        // 기본: 모든 호출이 노드(또는 블록 줄)로
        MermaidGraph all = new FlowchartGenerator().build(r);
        r.callSites.stream().filter(s -> FlowchartGenerator.drawsCall(r, s)).forEach(s -> assertTrue(all.has(s.id), s.id));
        CallSiteInfo publish = call(r, "publish(vo)");
        assertTrue(all.has("sg_" + publish.targetMethodId));
        // 사용자 exclude: 로그 전부 + publish (메서드 상자도 생략), 흐름도 머리에 주석으로 남김
        FlowchartGenerator gen = new FlowchartGenerator().exclude(List.of("LOG.*", "CaseAction.publish"));
        String mmd = gen.render(r, "TD"); // 내부 검증(빠진 노드)도 exclude 기준으로 통과해야 함
        assertTrue(mmd.contains("    %% flowchart-exclude (사용자 설정으로 뺀 호출·메서드): LOG.*, CaseAction.publish\n"), mmd);
        assertFalse(mmd.contains("로그<br/>"), mmd);
        assertFalse(mmd.contains("publish(vo)"), mmd);
        assertFalse(mmd.contains("sg_" + publish.targetMethodId), mmd);
        assertTrue(mmd.contains("[\"#35;5 release()\"]") && mmd.contains("[\"#35;7 release()\"]"), mmd);
        // 설정 키: output.flowchart-exclude
        Path f = Files.createTempFile("cfg", ".yml");
        Files.writeString(f, "target: { source-root: x, class: A }\noutput:\n  flowchart-exclude: [\"*Util.*\", \"LOG.debug\"]\n");
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertEquals(List.of("*Util.*", "LOG.debug"), cfg.output.flowchartExclude);
    }

    @Test
    void catchAndFinallyBodiesAreDrawn() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "throwCases");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        ControlBlock c1 = r.blocks.stream().filter(b -> b.type == analyzer.model.BlockType.CATCH).findFirst().orElseThrow();
        CallSiteInfo logError = call(r, "logError(e)");
        // catch 노드 → 본문(logError) — 본문을 그리는 메서드로 ==>
        assertTrue(mmd.contains(c1.id + " --> " + g.nodeOf(logError.id)), mmd);
        assertTrue(g.has("sg_" + logError.targetMethodId));
        // 잡히는 throw → 그 catch (점선)
        ControlBlock thrown = r.blocks.stream().filter(b -> b.type == analyzer.model.BlockType.THROW && c1.id.equals(b.caughtBy))
                .findFirst().orElseThrow();
        assertTrue(mmd.contains(thrown.id + " -.->|\"catch\"| " + c1.id), mmd);
    }
}
