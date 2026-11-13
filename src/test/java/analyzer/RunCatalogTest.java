package analyzer;

import analyzer.query.CatalogQuery;
import analyzer.query.QueryMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * run 카탈로그 + 카탈로그 질의 (사용자: 공통화 — 각 Action 이 쓰는 메서드, 이 메서드를 쓰는 Action; 두 카탈로그 인터페이스(key·uses)가
 * 맞아야 함; 콘솔로 보고 결과도 남김). fixture EdgeAction.bizVo·bizVoOrder·utilBeans, ScpAppListAction.execute, util/ProductUtil.
 */
class RunCatalogTest {

    static void run(Path cat, String... args) throws Exception {
        String[] all = new String[args.length + 4];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--catalog-dir";
        all[args.length + 1] = cat.toString();
        all[args.length + 2] = "--output";
        all[args.length + 3] = cat.resolveSibling("out").toString();
        assertEquals(0, Main.run(all));
    }

    static void action(Path cat, String clazz, String method) throws Exception {
        run(cat, "--source-root", Fixtures.SOURCE_ROOT, "--class", clazz, "--method", method);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sameInterfaceAndQueries(@TempDir Path tmp) throws Exception {
        Path cat = tmp.resolve("catalog");
        action(cat, "EdgeAction", "bizVo");
        action(cat, "EdgeAction", "bizVoOrder");
        action(cat, "EdgeAction", "utilBeans");
        action(cat, "ScpAppListAction", "execute");
        run(cat, "--source-root", Fixtures.SOURCE_ROOT, "--class", "ProductUtil", "--all-methods");

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        // 공통 인터페이스: run 카탈로그 항목과 Util 기능 목록 메서드 모두 key(analysis.yaml 의 메서드 키) + uses(key 목록)
        Map<String, Object> run = yaml.readValue(cat.resolve("run/EdgeAction.yml").toFile(), Map.class);
        Map<String, Object> biz = ((List<Map<String, Object>>) run.get("entries")).stream()
                .filter(e -> "com.example.action.EdgeAction#bizVo(HttpServletRequest)".equals(e.get("key"))).findFirst().orElseThrow();
        assertEquals(List.of("com.example.biz.NoticeWriteBiz#write(NoticeVO,String)", "com.example.dao.NoticeDAO#insert(NoticeVO)",
                "com.example.dao.NoticeDAO#insertHistory(NoticeVO)"), biz.get("uses")); // VO setter 는 제외
        Map<String, Object> util = yaml.readValue(cat.resolve("ProductUtil.yml").toFile(), Map.class);
        Map<String, Object> info = ((List<Map<String, Object>>) util.get("methods")).get(0);
        assertEquals("com.example.util.ProductUtil#info(String)", info.get("key"));
        assertTrue(((List<String>) info.get("uses")).contains("com.example.dao.ProductDAO#select(String)"));

        // note 보존 + 다른 진입 메서드 항목 유지
        ((List<Map<String, Object>>) run.get("entries")).get(0).put("note", "공통화 후보");
        yaml.writeValue(cat.resolve("run/EdgeAction.yml").toFile(), run);
        action(cat, "EdgeAction", "bizVo");
        Map<String, Object> again = yaml.readValue(cat.resolve("run/EdgeAction.yml").toFile(), Map.class);
        assertEquals(3, ((List<?>) again.get("entries")).size());
        assertEquals("공통화 후보", ((List<Map<String, Object>>) again.get("entries")).get(0).get("note"));

        CatalogQuery q = CatalogQuery.load(cat);
        assertTrue(q.basis().contains("Action 2개 · 진입 메서드 4개 · Util 기능 목록 1개"), q.basis());
        // who-uses: 쓰는 Action + 쓰는 Util 메서드
        var who = q.whoUses("ProductInfoBizImpl.getInfo", CatalogQuery.Filters.NONE).rows();
        assertEquals(1, who.size());
        assertEquals("EdgeAction.utilBeans", who.get(0).get(2));
        assertTrue(who.get(0).get(3).contains("ProductUtil.info"), who.toString());
        // common 2 = bizVo·bizVoOrder 가 함께 쓰는 Biz·DAO
        var common = q.common(2, CatalogQuery.Filters.NONE).rows();
        assertEquals(List.of("NoticeWriteBiz.write(NoticeVO,String)", "NoticeDAO.insert(NoticeVO)", "NoticeDAO.insertHistory(NoticeVO)"),
                common.stream().map(r -> r.get(0)).sorted((a, b) -> a.startsWith("NoticeWriteBiz") ? -1 : b.startsWith("NoticeWriteBiz") ? 1 : a.compareTo(b)).toList());
        assertEquals(3, q.shared(List.of("EdgeAction.bizVo", "EdgeAction.bizVoOrder"), CatalogQuery.Filters.NONE).rows().size());
        // class= 필터
        assertEquals(2, q.common(2, new CatalogQuery.Filters("*DAO", null)).rows().size());
        // unused: Util 기능 목록 중 어느 Action 도 안 쓰는 메서드 (utilBeans 는 refresh 를 안 씀)
        assertEquals(List.of(List.of("ProductUtil", "ProductUtil.refresh(String)")), q.unused("ProductUtil").rows());

        // 콘솔 + 파일 저장
        Path md = tmp.resolve("who.md"), csv = tmp.resolve("who.csv");
        assertEquals(0, QueryMain.run(new String[]{"who-uses", "NoticeDAO", "--catalog-dir", cat.toString(), "--md", md.toString(), "--csv", csv.toString()}));
        assertTrue(Files.readString(md).contains("| NoticeDAO.insert(NoticeVO) | 2 | EdgeAction.bizVo, EdgeAction.bizVoOrder |"), Files.readString(md));
        assertTrue(Files.readString(csv).contains("\"NoticeDAO.insertHistory(NoticeVO)\",\"2\""));
        assertFalse(Files.readString(md).contains("NoticeVO.set"));

        // coverage: run 안 한 Action · 기능 목록 없는 Util (사용자: 데이터가 완벽해야)
        Path cov = tmp.resolve("cov.md");
        assertEquals(0, QueryMain.run(new String[]{"coverage", "--config", "config/analyzer.yml", "--catalog-dir", cat.toString(), "--md", cov.toString()}));
        String c = Files.readString(cov);
        assertTrue(c.contains("| run 카탈로그 없음 | com.example.action.CaseAction |"), c);
        assertFalse(c.contains("com.example.action.EdgeAction |"), c); // 분석해 둔 Action 은 빠짐 없음
        assertFalse(c.contains("| Util 기능 목록 없음 | com.example.util.ProductUtil |"), c);
    }
}
