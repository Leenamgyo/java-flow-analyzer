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

    static final String REQ = analyzer.mermaid.MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS.get("req"));
    static final String FN = analyzer.mermaid.MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS.get("fn"));

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
        // 로그도 코드 그대로 한 줄씩, "로그" 제목 없이 이어지는 코드와 같은 블록 (사용자 요구: 제목·배경색 구분 없앰)
        assertTrue(mmd.contains(info.id + "[\"#35;1 LOG.info(#quot;start #quot; + request.getRequestURI())<br/>"
                + "#35;2 LOG.debug(#quot;items={}#quot;, items.size())<br/>"), mmd);
        assertFalse(mmd.contains("로그<br/>"), mmd);
        assertFalse(mmd.contains("바인딩<br/>"), mmd);
        // 네모(코드 상자)는 색 구분 없음 — 같은 스타일
        assertTrue(mmd.contains("classDef log fill:#f8fafc,stroke:#64748b") && mmd.contains("classDef bind fill:#f8fafc,stroke:#64748b")
                && mmd.contains("classDef codeline fill:#f8fafc,stroke:#64748b"), mmd);
    }

    @Test
    void requestParametersAndNestedBindingFunctionsShareTheBindingBlock() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        MermaidGraph g = new FlowchartGenerator().build(r);
        CallSiteInfo read = call(r, "request.getParameter(\"id\")");
        CallSiteInfo toSearch = call(r, "toSearch(request)");
        // 요청 파라미터·바인딩 함수·로그 모두 한 코드 블록 (종류로 나누지 않음)
        assertEquals(g.nodeOf(read.id), g.nodeOf(toSearch.id));
        String mmd = new FlowchartGenerator().render(r, "TD");
        // 요청 값(request.이름)만 글자색 req, 바인딩 함수 = 글자색 fn, 안쪽 바인딩 함수는 │ 로 한 단계 더 중첩
        assertTrue(mmd.contains("<br/>#35;3 String id = " + REQ + "request.id</font><br/>"
                + FN + "▸ #35;4 SearchVO vo = toSearch(request)</font><br/>"
                + "│ keyword = trimmed(request.q)<br/>"
                + FN + "│ ▸ trimmed(request.q)</font><br/>"
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
            assertTrue(mmd.contains("subgraph sg_" + b.id + "[\"↻ "), mmd);
            assertFalse(mmd.contains("반복"), mmd); // "반복" 글자 없음 — 색으로 구분
            assertTrue(mmd.contains("    " + b.id + "{{\"") || mmd.contains(b.id + "{{\""), mmd); // 조건 노드 = 육각형 (if 는 마름모)
            assertTrue(g.idsUnder("sg_" + b.id).contains(b.id));
        }
        String inWhile = g.nodeOf(r.callSites.stream().filter(c -> w.id.equals(c.controlBlockId)).findFirst().orElseThrow().id);
        String inDo = g.nodeOf(r.callSites.stream().filter(c -> d.id.equals(c.controlBlockId)).findFirst().orElseThrow().id);
        assertTrue(g.idsUnder("sg_" + w.id).contains(inWhile));
        // while: 조건 → loop → 본문 → next → 조건, 조건 → done
        assertTrue(loopEdge(mmd, w.id + " --> " + inWhile), mmd);
        String whileLast = g.nodeOf(r.callSites.stream().filter(c -> w.id.equals(c.controlBlockId)).reduce((a, b) -> b).orElseThrow().id);
        assertTrue(loopEdge(mmd, whileLast + " -->|\"↻\"| " + w.id), mmd);
        // 빠져나가는 선 = 일반 선 (라벨·색 없음) / do-while: 본문이 먼저 → 조건 → loop 이면 본문으로
        assertTrue(mmd.contains("    " + w.id + " --> " + inDo + "\n") && !loopEdge(mmd, w.id + " --> " + inDo), mmd);
        assertTrue(mmd.contains(inDo + " --> " + d.id), mmd);
        assertTrue(loopEdge(mmd, d.id + " -->|\"↻\"| " + inDo), mmd);
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

    @Test
    void onlyRequestValuesAreColoredNotTheWholeStatement() {
        // int n = Integer.parseInt(request.size) — Integer.parseInt(…) 는 요청 항목이 아니라 칠하지 않는다
        assertEquals("int n = Integer.parseInt(" + REQ + "request.size</font>)",
                MermaidGraph.requestSpans("int n = Integer.parseInt(request.size)", MermaidGraph.TEXT_COLORS.get("req")));
        assertEquals("String k = " + REQ + "request.a</font> + " + REQ + "request.b</font>",
                MermaidGraph.requestSpans("String k = request.a + request.b", MermaidGraph.TEXT_COLORS.get("req")));
        assertEquals("String m = " + REQ + "request.mode ?: #quot;NEW#quot;</font>.trim()",
                MermaidGraph.requestSpans("String m = request.mode ?: \"NEW\".trim()", MermaidGraph.TEXT_COLORS.get("req")));
        assertEquals("String w = " + REQ + "request[keyVO.getKeyword()]</font>",
                MermaidGraph.requestSpans("String w = request[keyVO.getKeyword()]", MermaidGraph.TEXT_COLORS.get("req")));
        // 요청 객체의 다른 메서드 호출은 요청 값 표기가 아니다
        assertEquals("String u = request.getRequestURI()",
                MermaidGraph.requestSpans("String u = request.getRequestURI()", MermaidGraph.TEXT_COLORS.get("req")));
    }

    @Test
    void colorThemeLightByDefaultAndDarkOnRequest() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "flowRules");
        String dark = new FlowchartGenerator().theme("dark").render(r, "TD");
        assertTrue(dark.startsWith("%%{init: {\"theme\": \"dark\""), dark);
        assertTrue(dark.contains("classDef codeline " + FlowchartGenerator.DARK_CLASS_DEFS.get("codeline")), dark);
        assertTrue(dark.contains(analyzer.mermaid.MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS_DARK.get("req"))), dark); // 다크 글자색
        String light = new FlowchartGenerator().render(r, "TD"); // 기본 = light (흰 바탕 — 사용자 요구)
        assertFalse(light.contains("\"theme\": \"dark\""), light);
        assertTrue(light.contains("classDef codeline " + FlowchartGenerator.CLASS_DEFS.get("codeline")), light);
        assertTrue(light.contains(analyzer.mermaid.MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS.get("req"))), light);
        // 분할 출력도 같은 테마
        new FlowchartGenerator().theme("dark").renderSectioned(r, "TD", "dagre", 60).sections().forEach(p -> assertTrue(p.mermaid().contains("\"theme\": \"dark\""), p.file()));
        // 설정 키 output.color-theme, 잘못된 값은 오류
        assertEquals("light", new AnalyzerConfig().output.colorTheme);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new FlowchartGenerator().theme("blue"));
    }

    /** 렌더링된 간선 줄(edge)이 있고 반복 색(linkStyle 목록)에 들어 있는지 — 간선 번호 = 간선 줄 순서 */
    static boolean loopEdge(String mmd, String edge) {
        List<String> edges = mmd.lines().filter(l -> l.matches("\\s+\\S+ (-->|==>|-\\.->).*")).map(String::strip).toList();
        int idx = edges.indexOf(edge);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("linkStyle ([\\d,]+) stroke").matcher(mmd);
        return idx >= 0 && m.find() && java.util.Arrays.asList(m.group(1).split(",")).contains(String.valueOf(idx));
    }
}
