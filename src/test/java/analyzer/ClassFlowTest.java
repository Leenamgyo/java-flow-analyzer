package analyzer;

import analyzer.classflow.ClassFlowMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 전체 클래스 흐름도 (./gradlew runClass) — Action 분석과 따로, flowchart.html 하나만. fixture util/ProductUtil */
class ClassFlowTest {

    @SuppressWarnings("unchecked")
    @Test
    void allMethodsInOneFlowchart(@TempDir Path tmp) throws Exception {
        assertEquals(0, ClassFlowMain.run(new String[]{"--source-root", Fixtures.SOURCE_ROOT, "--class", "ProductUtil",
                "--output", tmp.toString()}));
        Path dir = tmp.resolve("class").resolve("ProductUtil");
        try (var files = Files.list(dir)) {
            assertEquals(List.of("flowchart.html"), files.map(p -> p.getFileName().toString()).toList()); // VO·spec 등 없음
        }
        assertFalse(Files.exists(tmp.resolve("ProductUtil")), "Action 분석 결과 폴더와 섞이지 않음");
        String html = Files.readString(dir.resolve("flowchart.html"));
        int a = html.indexOf("const FLOW = ") + "const FLOW = ".length();
        Map<String, Object> data = new ObjectMapper().readValue(html.substring(a, html.indexOf(";</script>", a)), Map.class);
        assertEquals(6, ((List<String>) data.get("entries")).size()); // 메서드 6개가 모두 진입
        assertEquals(true, data.get("allRight")); // 불린 메서드는 모두 오른쪽 열
        assertEquals(6, ((List<String>) data.get("collapsedPreset")).size()); // Util 메서드 상자(ProductUtil 6개)는 처음에 접힘
        // 같은 Biz 구현·DAO 는 한 번만 (여러 메서드가 공유)
        assertEquals(1, count(html, "ProductInfoBizImpl.getInfo() «Service»"));
        assertEquals(1, count(html, "ProductDAO.select() «DAO»"));
    }

    @Test
    void sharedCoreFindsSameDependenciesAsSingleEntry() throws Exception {
        var cfg = analyzer.config.ConfigLoader.defaults();
        cfg.target.sourceRoot = Fixtures.SOURCE_ROOT;
        cfg.target.className = "ProductUtil";
        var all = ClassFlowMain.analyze(cfg);
        assertEquals(6, all.entryMethodIds.size());
        all.entryMethodIds.forEach(id -> assertTrue(all.isEntry(id)));
        assertTrue(all.callSites.stream().anyMatch(c -> "REFLECTION".equals(c.resolutionBasis)));
        assertTrue(all.callSites.stream().anyMatch(c -> "BEAN_NAME".equals(c.implementationBasis) && c.implementationMethodId != null));
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) {
            n++;
        }
        return n;
    }

    /** --apply: catalog/<클래스>.yml 의 hidden(메서드·패턴)을 처음부터 숨김으로. --apply 없으면 카탈로그를 보지 않는다 */
    @SuppressWarnings("unchecked")
    @Test
    void applyCatalogHidden(@TempDir Path tmp) throws Exception {
        Path catalog = tmp.resolve("catalog");
        Files.createDirectories(catalog);
        Files.writeString(catalog.resolve("ProductUtil.yml"), "class: com.example.util.ProductUtil\nmethods: []\n"
                + "hidden:\n  - ProductInfoBizImpl.getInfo\n  - \"*CacheBiz\"\n  - NoSuch.method\n");
        Path cfg = tmp.resolve("c.yml");
        Files.writeString(cfg, "target:\n  source-root: " + Fixtures.SOURCE_ROOT + "\n  class: ProductUtil\n"
                + "output:\n  directory: " + tmp.resolve("out") + "\n  catalog-dir: " + catalog + "\n");
        assertEquals(0, ClassFlowMain.run(new String[]{"--config", cfg.toString(), "--apply"}));
        Map<String, Object> data = data(tmp.resolve("out/class/ProductUtil/flowchart.html"));
        List<String> preset = (List<String>) data.get("hiddenPreset");
        assertEquals(2, preset.size(), preset.toString()); // BizImpl.getInfo + CacheBiz.getInfo (NoSuch 는 경고만)
        String all = Files.readString(tmp.resolve("out/class/ProductUtil/flowchart.html"));
        for (String id : preset) {
            assertTrue(all.contains("\"group\":\"" + id + "\""), id);
        }
        // --apply 없으면 카탈로그를 보지 않음
        assertEquals(0, ClassFlowMain.run(new String[]{"--config", cfg.toString()}));
        assertFalse(data(tmp.resolve("out/class/ProductUtil/flowchart.html")).containsKey("hiddenPreset"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Path html) throws Exception {
        String s = Files.readString(html);
        int a = s.indexOf("const FLOW = ") + "const FLOW = ".length();
        return new ObjectMapper().readValue(s.substring(a, s.indexOf(";</script>", a)), Map.class);
    }
}
