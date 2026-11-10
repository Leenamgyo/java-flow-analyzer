package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.serializer.OutputWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** flowchart.html (output.flowchart-html): Mermaid 와 같은 그래프를 내장 뷰어로 — 형식은 따로 켜고 끔 */
class HtmlFlowchartTest {

    private static AnalyzerConfig cfg(Path tmp) {
        AnalyzerConfig cfg = Fixtures.config("ScpAppListAction", "execute");
        cfg.output.directory = tmp.toString();
        cfg.output.mermaidCli = tmp.resolve("no-mmdc").toString();
        return cfg;
    }

    /** 페이지에 내장된 그래프 데이터 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(String html) throws Exception {
        String start = "const FLOW = ";
        int a = html.indexOf(start) + start.length();
        int b = html.indexOf(";</script>", a);
        return new ObjectMapper().readValue(html.substring(a, b), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static void collect(List<Object> items, List<Map<String, Object>> nodes, Set<String> groups) {
        for (Object o : items) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.containsKey("group")) {
                groups.add((String) m.get("group"));
                collect((List<Object>) m.get("children"), nodes, groups);
            } else {
                nodes.add(m);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void htmlCarriesSameGraphAsMermaid(@TempDir Path tmp) throws Exception {
        AnalyzerConfig cfg = cfg(tmp);
        AnalysisResult r = Main.analyze(cfg);
        Path dir = Main.outputDir(cfg, "ScpAppListAction");
        new OutputWriter().write(r, cfg, dir);
        String html = Files.readString(dir.resolve("flowchart.html"));
        assertTrue(html.contains("ELK"), "배치 엔진 내장 (오프라인)");
        assertEquals(10, ((Number) data(html).get("hideNodes")).intValue(), "작은 세부 메서드 생략 기준 (기본 10)");
        assertFalse(html.contains("cdn.") || html.contains("https://unpkg"), "외부 스크립트 없음");
        Map<String, Object> d = data(html);
        // 데이터 안에 '<' 가 그대로 없어야 <script> 가 깨지지 않는다
        String raw = html.substring(html.indexOf("const FLOW = "), html.indexOf(";</script>", html.indexOf("const FLOW = ")));
        assertFalse(raw.contains("<"));

        MermaidGraph g = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                .maxLineLength(cfg.output.maxLineLength).maxNodeLines(cfg.output.maxNodeLines).checked(r);
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        collect((List<Object>) d.get("root"), nodes, groups);
        // 노드는 Mermaid 그래프와 정확히 같은 집합, 한 번씩
        List<String> ids = nodes.stream().map(n -> (String) n.get("id")).toList();
        assertEquals(g.nodeIds(), new HashSet<>(ids));
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertTrue(groups.contains("sg_" + r.entryMethodId));
        assertEquals(g.edges().size(), ((List<Object>) d.get("edges")).size());
        // 세부 호출(==>)은 상자 색 detail + 이동 대상
        assertTrue(nodes.stream().anyMatch(n -> "detail".equals(n.get("cls")) && n.get("goto") != null));
        // 노드 상세의 파일:라인
        assertTrue(nodes.stream().anyMatch(n -> ((List<String>) n.get("locs")).stream().anyMatch(l -> l.contains("ScpAppListAction.java:"))));
        // 요청 값 표기만 req 색
        boolean req = false;
        for (Map<String, Object> n : nodes) {
            for (List<List<String>> line : (List<List<List<String>>>) n.get("lines")) {
                for (List<String> seg : line) {
                    if ("req".equals(seg.get(1))) {
                        assertTrue(seg.get(0).startsWith("request"), seg.toString()); // request.이름 ?: 기본값 · request[식]
                        req = true;
                    }
                }
            }
        }
        assertTrue(req);
        // Service 호출에 넘기는 VO 와 set 코드 (값 출처를 거슬러 올라가지 않고 set 코드 그대로) — 반환값으로 받은 VO 는 만든 함수 안에서
        List<Map<String, Object>> vo = nodes.stream().filter(n -> n.containsKey("voIn"))
                .flatMap(n -> ((List<Map<String, Object>>) n.get("voIn")).stream()).toList();
        assertEquals(1, vo.size(), vo.toString());
        assertEquals("AppListCommand cmd", vo.get(0).get("title"));
        assertTrue(((String) vo.get(0).get("via")).startsWith("createCommand("));
        assertEquals(List.of("mcc = parseParameter(mcc)", "startNum = startNum"), vo.get(0).get("lines"));
        // 단계 보기도
        assertTrue(Files.exists(dir.resolve("phase-flowchart.html")));
    }

    @Test
    void formatsAreToggledSeparately(@TempDir Path tmp) throws Exception {
        AnalyzerConfig cfg = cfg(tmp);
        AnalysisResult r = Main.analyze(cfg);
        Path dir = Main.outputDir(cfg, "ScpAppListAction");
        // HTML 만
        cfg.output.mermaid = false;
        new OutputWriter().write(r, cfg, dir);
        assertTrue(Files.exists(dir.resolve("flowchart.html")));
        assertFalse(Files.exists(dir.resolve("flowchart.mmd")));
        // Mermaid 만 — 이전 실행의 HTML 은 지운다
        cfg.output.mermaid = true;
        cfg.output.flowchartHtml = false;
        new OutputWriter().write(r, cfg, dir);
        assertTrue(Files.exists(dir.resolve("flowchart.mmd")));
        assertFalse(Files.exists(dir.resolve("flowchart.html")));
        assertFalse(Files.exists(dir.resolve("phase-flowchart.html")));
    }
}
