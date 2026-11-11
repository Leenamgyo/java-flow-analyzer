package analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Util 기능 목록 (사용자: ContentUtil 은 메서드에서 내려가는 계층이 아니라 유틸 전체의 기능 분석, 빌드를 계속해도 남아 관리,
 * void 목록도). fixture util/ProductUtil.
 */
class UtilCatalogTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> method(Map<String, Object> doc, String sig) {
        return ((List<Map<String, Object>>) doc.get("methods")).stream().filter(m -> sig.equals(m.get("signature"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    @Test
    void catalogIsCreatedThenUpdatedKeepingNotesAndRemovedMethods(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("catalog");
        // catalog-dir 는 설정으로 — 임시 설정 파일
        Path cfg = tmp.resolve("c.yml");
        Files.writeString(cfg, "target:\n  source-root: " + Fixtures.SOURCE_ROOT + "\n  class: ProductUtil\n  all-methods: true\n"
                + "output:\n  catalog-dir: " + dir + "\n");
        assertEquals(0, Main.run(new String[]{"--config", cfg.toString()}));
        Path file = dir.resolve("ProductUtil.yml");
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        Map<String, Object> doc = yaml.readValue(file.toFile(), Map.class);
        assertEquals("com.example.util.ProductUtil", doc.get("class"));
        // 기능 하나 = 메서드 하나: 의존 (빈 이름으로 이은 Biz 구현 → DAO)
        Map<String, Object> info = method(doc, "info(String id)");
        assertEquals("String", info.get("returns"));
        assertEquals(List.of("ProductInfoBiz.getInfo → ProductInfoBizImpl.getInfo"), info.get("services"));
        assertEquals(List.of("ProductDAO.select"), info.get("daos"));
        assertTrue(((List<String>) info.get("beans")).get(0).startsWith("빈 이름 \"productInfoBiz\""));
        assertTrue(((List<String>) method(doc, "infoReflect(String id)").get("reflection")).get(0).contains("→ ProductInfoBizImpl.getInfo"));
        assertTrue(((List<String>) method(doc, "unknown(String id)").get("unresolved")).get(0).contains("noSuchBiz"));
        // void 목록
        assertEquals(List.of("refresh(String id)"), doc.get("void-methods"));

        // 사람이 note 를 적고, 이전 버전에만 있던 메서드가 있었다고 치고 다시 실행
        String edited = Files.readString(file).replaceFirst("note: \"\"", "note: 상품 정보 조회 — 신규 API 로 이전 예정");
        Map<String, Object> old = yaml.readValue(edited, Map.class);
        ((List<Map<String, Object>>) old.get("methods")).add(Map.of("signature", "oldMethod()", "returns", "void", "note", "옛 기능"));
        new ObjectMapper(new YAMLFactory()).writeValue(file.toFile(), old);
        assertEquals(0, Main.run(new String[]{"--config", cfg.toString()}));
        Map<String, Object> again = yaml.readValue(file.toFile(), Map.class);
        assertEquals("상품 정보 조회 — 신규 API 로 이전 예정", method(again, "info(String id)").get("note")); // 보존
        Map<String, Object> gone = method(again, "oldMethod()");
        assertEquals(true, gone.get("removed")); // 지우지 않고 표시
        assertEquals("옛 기능", gone.get("note"));
        // 같은 소스로 다시 돌리면 내용이 같다 (시각 등 실행 정보 없음 — git diff 가 변경만 보이게)
        String once = Files.readString(file);
        assertEquals(0, Main.run(new String[]{"--config", cfg.toString()}));
        assertEquals(once, Files.readString(file));
    }

    /** 저장소에서 관리하는 설정 파일 config/class-survey.yml 이 계속 유효한지 (catalog 는 임시 폴더로) */
    @Test
    void managedConfigFileIsValid() throws Exception {
        var cfg = analyzer.config.ConfigLoader.load(Path.of("config/class-survey.yml"));
        assertTrue(cfg.target.allMethods);
        assertEquals("catalog", cfg.output.catalogDir);
    }
}
