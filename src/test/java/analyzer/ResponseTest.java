package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.response.ResponseAnalyzer;
import analyzer.response.ResponseModel;
import analyzer.response.ResponseReportWriter;
import analyzer.serializer.SpecWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Response(JSP) — 사용자가 정한 JSP 파일명으로 찾아 XML 값·출처, spec.md 2. Response 정의 */
class ResponseTest {

    private static AnalyzerConfig cfg(String... jsp) {
        AnalyzerConfig c = Fixtures.config("AppDetailAction", "execute");
        c.analysis.expandClasses = List.of("CountryUtil");
        c.response.jsp = List.of(jsp);
        return c;
    }

    @Test
    void findsJspByFileNameAndTracesValues() {
        AnalyzerConfig c = cfg("appDetail.jsp");
        AnalysisResult r = Main.analyze(c);
        ResponseModel m = ResponseAnalyzer.analyze(c, r);
        ResponseModel.Jsp j = m.jsps.get(0);
        assertEquals("FOUND", j.status);
        assertEquals("src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp", j.file);
        assertEquals(List.of("src/main/webapp/WEB-INF/jsp/common/footer.jsp"), j.includes); // include 펼침
        assertEquals(List.of("country", "appList"), m.attributes.stream().map(ResponseModel.Attribute::name).toList());
        String report = new ResponseReportWriter().render(r, m);
        assertTrue(report.contains("resultCode ← attribute resultCode — 분석한 코드에서 넣는 곳 없음"), report);

        String spec = new SpecWriter().render(r, m);
        assertTrue(spec.contains("""
                ## 2. Response 정의

                - JSP: `src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp`
                - Content-Type: `text/xml; charset=UTF-8`

                ```xml
                <response>
                  <result code="${resultCode}">OK</result>
                  <country>${country}</country>
                  <lang>${param.lang}</lang>
                  <!-- 조건: ${not empty appList} -->
                  <apps>
                    <!-- 반복: ${appList} (app) -->
                    <app id="${app}" seq="${st.index}">
                      <name>${app}</name>
                    </app>
                    <!-- /반복 -->
                  </apps>
                  <!-- /조건 -->
                  <version>1.0</version>
                </response>
                ```
                """), spec);
    }

    @Test
    void realWorldListJsp() {
        AnalyzerConfig c = cfg("appList.jsp");
        AnalysisResult r = Main.analyze(c);
        String spec = new SpecWriter().render(r, ResponseAnalyzer.analyze(c, r));
        for (String line : List.of(
                "- Content-Type: `text/xml; charset=UTF-8`",
                "  <currencyInfo>\n    <currencyUnit>${currencyExtVO.currencySignText}</currencyUnit>",
                "  <!-- 반복: ${resultVO} (resultVO) -->\n  <appInfo>",
                "    <productName><![CDATA[${resultVO.contentName}]]></productName>",
                "    <!-- 조건: ${not empty resultVO.stickerInfo} -->\n    <stickerImgCount>",
                "  <serverType>${vf:getServerType()}</serverType>")) {
            assertTrue(spec.contains(line), line + "\n" + spec);
        }
        assertTrue(!spec.contains("c:forEach") && !spec.contains("c:set") && !spec.contains("c:if") && !spec.contains("installInfo"), spec);
    }

    @Test
    void jspByFilePath() {
        String path = "src/test/resources/sample-project/src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp";
        for (String p : List.of(path, java.nio.file.Path.of(path).toAbsolutePath().toString())) {
            AnalyzerConfig c = cfg(p);
            c.response.webRoots = List.of("/nonexistent"); // 경로 지정이면 웹 루트 검색과 무관
            ResponseModel m = ResponseAnalyzer.analyze(c, Main.analyze(c));
            ResponseModel.Jsp j = m.jsps.get(0);
            assertEquals("FOUND", j.status, p);
            assertEquals("src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp", j.file);
            assertEquals(List.of("src/main/webapp/WEB-INF/jsp/common/footer.jsp"), j.includes); // "/WEB-INF/…" include = WEB-INF 의 부모 기준
        }
    }

    @Test
    void notFoundIsReportedNotGuessed() {
        AnalyzerConfig c = cfg("nothing.jsp");
        AnalysisResult r = Main.analyze(c);
        ResponseModel m = ResponseAnalyzer.analyze(c, r);
        assertEquals("NOT_FOUND", m.jsps.get(0).status);
        assertTrue(new SpecWriter().render(r, m).contains("> nothing.jsp — NOT_FOUND"));
    }

    @Test
    void noJspNoResponseSection() {
        AnalysisResult r = Main.analyze(cfg());
        assertTrue(!new SpecWriter().render(r).contains("## 2. Response"));
    }
}
