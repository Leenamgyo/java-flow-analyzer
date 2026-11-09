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
        c.analysis.expandClasses = List.of("*Action.ParamMgr", "CountryUtil");
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

                JSP: `src/main/webapp/WEB-INF/jsp/vas/appDetail.jsp`

                | 항목 | 필수 | 반복 | 값 |
                |---|---|---|---|
                | response/result@code | Y |  | resultCode (Action 에서 넣는 곳 없음) |
                | response/result | Y |  | 고정 OK |
                | response/country | Y |  | country |
                | response/lang | Y |  | request.lang |
                | response/apps/app@id | N | Y | appList[] |
                | response/apps/app@seq | N | Y | 순번 (st.index) |
                | response/apps/app/name | N | Y | appList[] |
                | response/version | Y |  | 고정 1.0 |
                """), spec);
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
