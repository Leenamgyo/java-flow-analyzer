package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.RequestParameterInfo;
import analyzer.serializer.CallTreeWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 호출 없는 변수 선언(초기값) 표시 · 기본값이 함수 호출인 요청 읽기 · 로직 있는 읽기 함수 표시 */
class InitialValueAndReaderTest {

    private static final String REQ = MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS.get("req"));

    @Test
    void plainDeclarationsAreDrawnInFlowchartAndTree() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "initialValues");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        for (String code : List.of("boolean data = false", "int count = 0", "String mode = #quot;LIST#quot;", "String label",
                "long limit = MAX_ROWS * 2L", "List#lt;String#gt; names = new ArrayList#lt;#gt;()",
                "String result = mode + count + data + limit")) {
            assertTrue(mmd.contains("<br/>" + code + "<br/>") || mmd.contains("<br/>" + code + "\"]"), code + "\n" + mmd);
        }
        // 선언 = v1 (꼬리표 없음), 변경은 v2
        assertTrue(mmd.contains("data = true · data v2"), mmd);
        String tree = new CallTreeWriter(true).render(r);
        assertTrue(tree.contains("≔ boolean data = false"), tree);
        assertTrue(tree.contains("≔ String result = mode + count + data + limit"), tree);
    }

    static AnalyzerConfig config(Path tmp) throws Exception {
        Path f = tmp.resolve("rules.yml");
        Files.writeString(f, """
                target:
                  source-root: %s
                  class: ScVersionAction
                analysis:
                  expand-same-class-methods: false
                request-parameter-rules:
                  - class: StringUtil
                    methods:
                      - { name: getParameter, name-arg-index: 1, default-arg-index: 2, type: String }
                  - methods:
                      - { name: getClientVersion, name-arg-index: 1, type: String }
                """.formatted(Path.of(Fixtures.SOURCE_ROOT).toAbsolutePath()));
        AnalyzerConfig cfg = ConfigLoader.load(f);
        ConfigLoader.validate(cfg);
        return cfg;
    }

    @Test
    void functionDefaultAndLogicReader(@TempDir Path tmp) throws Exception {
        AnalysisResult r = Main.analyze(config(tmp));
        RequestParameterInfo sc = r.parameters.stream().filter(p -> "scVersion".equals(p.name)).findFirst().orElseThrow();
        assertEquals("VasUtil.getExceptionScVersion(appId)", sc.defaultValue);
        assertNull(sc.readerLogic, "StringUtil.getParameter = 읽기 + 기본값뿐");
        RequestParameterInfo cv = r.parameters.stream().filter(p -> "clientVersion".equals(p.name)).findFirst().orElseThrow();
        assertNotNull(cv.readerLogic);
        assertTrue(cv.readerLogic.contains("VasUtil.normalize"), cv.readerLogic);

        // 같은 규칙(default-arg-index: 2)으로 인자 2개 호출도: 기본값 없이 읽기, 이름 인자도 없는 호출은 규칙 미적용
        RequestParameterInfo page = r.parameters.stream().filter(p -> "page".equals(p.name)).findFirst().orElseThrow();
        assertNull(page.defaultValue);
        assertEquals("StringUtil.getParameter", page.extraction);
        assertEquals(List.of("appId", "scVersion", "mode", "clientVersion", "page"), r.parameters.stream().map(p -> p.name).toList());

        String mmd = new FlowchartGenerator().render(r, "TD");
        // 함수 호출 기본값도 요청 값 표기 전체가 한 색 (괄호 앞에서 끊기지 않음)
        assertTrue(mmd.contains("String scVersion = " + REQ + "request.scVersion ?: VasUtil.getExceptionScVersion(appId)</font>"), mmd);
        // 로직 있는 읽기 함수는 호출 코드 그대로 + 어떤 요청 값인지
        assertTrue(mmd.contains("String clientVersion = getClientVersion(request, #quot;clientVersion#quot;)  ⇒ "
                + REQ + "request.clientVersion</font>"), mmd);
    }

    /** 오버로드마다 규칙을 따로 적어도 (기본값 없는 규칙이 먼저여도) 인자 수에 맞는 규칙이 쓰인다 */
    @Test
    void separateRulesPerOverload(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rules2.yml");
        Files.writeString(f, """
                target:
                  source-root: %s
                  class: ScVersionAction
                analysis:
                  expand-same-class-methods: false
                request-parameter-rules:
                  - class: StringUtil
                    methods:
                      - { name: getParameter, name-arg-index: 1, type: String }
                      - { name: getParameter, name-arg-index: 1, default-arg-index: 2, type: String }
                """.formatted(Path.of(Fixtures.SOURCE_ROOT).toAbsolutePath()));
        AnalyzerConfig cfg = ConfigLoader.load(f);
        ConfigLoader.validate(cfg);
        AnalysisResult r = Main.analyze(cfg);
        RequestParameterInfo mode = r.parameters.stream().filter(p -> "mode".equals(p.name)).findFirst().orElseThrow();
        assertEquals("\"LIST\"", mode.defaultValue);
        RequestParameterInfo page = r.parameters.stream().filter(p -> "page".equals(p.name)).findFirst().orElseThrow();
        assertNull(page.defaultValue);
    }

    @Test
    void requestColorSurvivesLineWrap() {
        MermaidGraph g = new MermaidGraph().maxLineLength(40);
        g.node(g.root(), "A", List.of(MermaidGraph.styled("req",
                "#3 String scVersion = request.scVersion ?: VasUtil.getExceptionScVersion(appId, region)")), MermaidGraph.Shape.RECT, "req");
        String out = g.render("TD", java.util.Map.of());
        String label = out.lines().filter(l -> l.contains("A[")).findFirst().orElseThrow();
        // 줄이 나뉘어도 이어지는 줄의 요청 값 부분까지 칠한다
        for (String piece : label.substring(label.indexOf("[\"") + 2, label.lastIndexOf("\"]")).split("<br/>")) {
            if (piece.contains("VasUtil") || piece.contains("region")) {
                assertTrue(piece.contains(REQ), piece);
            }
        }
    }
}
