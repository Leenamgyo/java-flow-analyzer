package analyzer;

import analyzer.query.CatalogQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 일괄 카탈로그 --all-actions · --all-utils (사용자: 일괄로, 모든 메서드에 역연산) */
class BatchCatalogTest {

    @Test
    void allActionsAndUtilsThenUsage(@TempDir Path tmp) throws Exception {
        Path cat = tmp.resolve("catalog");
        assertEquals(0, Main.run(new String[]{"--source-root", Fixtures.SOURCE_ROOT, "--all-actions", "--all-utils",
                "--catalog-dir", cat.toString(), "--output", tmp.resolve("out").toString()}));
        // 진입 메서드를 정할 수 있는 Action/Controller 전부 (execute · 매핑 어노테이션)
        for (String a : new String[]{"ScpAppListAction", "AppDetailAction", "ScVersionAction", "SuperReadAction", "DaoPatternAction", "AppController"}) {
            assertTrue(Files.exists(cat.resolve("run/" + a + ".yml")), a);
        }
        assertFalse(Files.exists(cat.resolve("run/CaseAction.yml"))); // 진입 메서드 없음 → 건너뜀 (사유 출력)
        assertTrue(Files.exists(cat.resolve("ProductUtil.yml")));
        assertTrue(Files.exists(cat.resolve("CountryUtil.yml")));
        assertFalse(Files.exists(tmp.resolve("out")), "기본은 카탈로그만 (output/ 상세 없음)");

        CatalogQuery q = CatalogQuery.load(cat);
        assertTrue(q.basis().startsWith("기준: run 카탈로그 Action 6개 · 진입 메서드 6개 · Util 기능 목록 7개"), q.basis());
        // 역색인: 모든 메서드 → 쓰는 Action
        var usage = q.usage(CatalogQuery.Filters.NONE).rows();
        assertTrue(usage.stream().anyMatch(r -> r.get(1).equals("ScpAppDAO.selectApps(String)") && r.get(3).equals("AppController.list, ScpAppListAction.execute")), usage.toString());
        assertTrue(usage.stream().anyMatch(r -> r.get(1).startsWith("CountryUtil.resolveCountry") && r.get(3).contains("AppDetailAction.execute")), usage.toString());
    }
}
