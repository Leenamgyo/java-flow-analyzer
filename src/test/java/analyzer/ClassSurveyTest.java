package analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 클래스 조사 --all-methods (사용자: Action 대신 ContentUtil 같은 클래스의 메서드 전부와 얽힌 의존 조사). fixture util/ProductUtil */
class ClassSurveyTest {

    @Test
    void everyMethodIsAnalyzedAndDependenciesListed(@TempDir Path tmp) throws Exception {
        int code = Main.run(new String[]{"--source-root", Fixtures.SOURCE_ROOT, "--class", "ProductUtil", "--all-methods",
                "--output", tmp.toString()});
        assertEquals(0, code);
        Path dir = tmp.resolve("ProductUtil");
        String md = Files.readString(dir.resolve("dependencies.md"));
        // 메서드마다 행 + 그 메서드 폴더에 기존 출력
        for (String m : new String[]{"info", "infoInline", "infoReflect", "legacy", "unknown"}) {
            assertTrue(md.contains("| " + m + "(String id) | public |"), md);
            assertTrue(Files.exists(dir.resolve(m).resolve("analysis.yaml")), m);
            assertTrue(Files.exists(dir.resolve(m).resolve("flowchart.html")), m);
        }
        // 의존: 빈 이름으로 꺼낸 Biz 구현 → DAO, 역색인
        assertTrue(md.contains("| info(String id) | public | ProductInfoBiz.getInfo<br>ProductInfoBizImpl.getInfo | ProductDAO.select |"), md);
        assertTrue(md.contains("| ProductDAO.select | info, infoInline, infoReflect |"), md);
        assertTrue(md.contains("빈 이름 \"legacyProductBiz\" → com.example.biz.ProductInfoCacheBiz"), md);
        assertTrue(md.contains("리플렉션 `bean.getClass().getMethod(\"getInfo\", String.class).invoke(bean, id)` → ProductInfoBizImpl.getInfo"), md);
        // 못 찾은 빈은 '확정하지 못한 호출'
        assertTrue(md.substring(md.indexOf("## 4.")).contains("noSuchBiz"), md);
    }
}
