package analyzer;

import analyzer.config.ConfigLoader;
import analyzer.catalog.RunCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * run 카탈로그 + 사용처 역색인 (사용자: 공통화 — 각 Action 이 어떤 메서드를 쓰는지, 이 클래스의 메서드는 어디서 쓰는지).
 * fixture EdgeAction.bizVo (NoticeWriteBiz → NoticeDAO) · EdgeAction.utilBeans (ProductUtil → ProductInfoBizImpl → ProductDAO) ·
 * ScpAppListAction.execute.
 */
class RunCatalogTest {

    static void run(Path tmp, String clazz, String method) throws Exception {
        assertEquals(0, Main.run(new String[]{"--source-root", Fixtures.SOURCE_ROOT, "--class", clazz, "--method", method,
                "--output", tmp.resolve("out").toString(), "--catalog-dir", tmp.resolve("catalog").toString()}));
    }

    @SuppressWarnings("unchecked")
    @Test
    void runAccumulatesUsedMethodsAndUsageIsReversed(@TempDir Path tmp) throws Exception {
        run(tmp, "EdgeAction", "bizVo");
        run(tmp, "EdgeAction", "utilBeans");
        run(tmp, "ScpAppListAction", "execute");
        Path file = tmp.resolve("catalog/run/EdgeAction.yml");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<String, Object> doc = yaml.readValue(file.toFile(), Map.class);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) doc.get("entries");
        assertEquals(2, entries.size()); // 같은 Action 의 진입 메서드 둘이 누적
        Map<String, Object> biz = entries.stream().filter(e -> String.valueOf(e.get("method")).startsWith("bizVo(")).findFirst().orElseThrow();
        assertEquals("bizVo(HttpServletRequest request)", biz.get("method"));
        Map<String, List<String>> uses = (Map<String, List<String>>) biz.get("uses");
        assertEquals(List.of("insert(NoticeVO)", "insertHistory(NoticeVO)"), uses.get("com.example.dao.NoticeDAO"));
        assertTrue(uses.containsKey("com.example.biz.NoticeWriteBiz"));

        // note 를 적고 같은 진입 메서드를 다시 분석해도 보존, 다른 진입 메서드 항목은 그대로
        entries.get(0).put("note", "공통화 후보");
        yaml.writeValue(file.toFile(), doc);
        run(tmp, "EdgeAction", "bizVo");
        Map<String, Object> again = yaml.readValue(file.toFile(), Map.class);
        assertEquals(2, ((List<?>) again.get("entries")).size());
        assertEquals("공통화 후보", ((List<Map<String, Object>>) again.get("entries")).get(0).get("note"));

        // 역색인: 이 메서드를 쓰는 곳
        var cfg = ConfigLoader.defaults();
        cfg.output.catalogDir = tmp.resolve("catalog").toString();
        Map<String, Set<String>> who = RunCatalog.whoUses(cfg, "ProductDAO.select");
        assertEquals(Set.of("EdgeAction.utilBeans"), who.get("ProductDAO.select(String)"));
        assertEquals(Set.of("ScpAppListAction.execute"), RunCatalog.whoUses(cfg, "ScpAppDAO.selectApps").get("ScpAppDAO.selectApps(String)"));
        assertEquals(2, RunCatalog.whoUses(cfg, "NoticeDAO").size()); // 클래스 이름 = 그 클래스 메서드 전부
        Path usage = RunCatalog.writeUsage(cfg);
        String md = Files.readString(usage);
        assertTrue(md.contains("## ProductDAO"), md);
        assertTrue(md.contains("| select(String) | 1 | EdgeAction.utilBeans |"), md);
    }
}
