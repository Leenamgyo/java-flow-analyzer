package analyzer;

import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.HtmlFlowchart;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Biz 도 VO 를 추적 (사용자 요구). fixture EdgeAction.bizVo → NoticeWriteBiz.write(vo, body) → NoticeDAO.insert(vo) /
 * insertHistory(history). 내장 기본 조건처럼 Biz·DAO 를 전개한다.
 */
class BizVoTest {

    static AnalysisResult analyze() {
        return Fixtures.analyze("EdgeAction", "bizVo", c -> c.analysis.expandRoles = List.of("Service", "Biz", "DAO", "Repository", "Mapper"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> vo(AnalysisResult r, String callStartsWith) {
        CallSiteInfo s = r.callSites.stream().filter(c -> c.expression.startsWith(callStartsWith)).findFirst().orElseThrow();
        MermaidGraph g = new FlowchartGenerator().checked(r);
        return analyzer.mermaid.HtmlFlowchartAccess.voInputs(r, g, s);
    }

    @Test
    void bizIsDrawnAsBoxNotFoldedIntoBindingFunction() {
        AnalysisResult r = analyze();
        MermaidGraph g = new FlowchartGenerator().checked(r);
        String biz = r.methods().stream().filter(m -> m.name.equals("write")).findFirst().orElseThrow().id;
        String insert = r.methods().stream().filter(m -> m.name.equals("insert")).findFirst().orElseThrow().id;
        assertTrue(g.has(biz), "Biz 메서드 상자");
        assertTrue(g.has(insert), "Biz 가 부르는 DAO 상자");
        assertFalse(FlowchartGenerator.bindingFunctions(r).contains(biz));
    }

    @Test
    void voIsTrackedThroughBiz() {
        AnalysisResult r = analyze();
        // Action → Biz: Biz 안에서 나중에 하는 set(body) 은 빼고
        List<Map<String, Object>> toBiz = vo(r, "noticeWriteBiz.write(");
        assertEquals(1, toBiz.size());
        assertEquals(List.of("title = request.title"), toBiz.get(0).get("lines"));
        // Biz → DAO: 받은 VO(파라미터) → Action 의 vo 로 이어 Action·Biz 의 set 전부
        List<Map<String, Object>> toDao = vo(r, "noticeDAO.insert(");
        assertEquals(1, toDao.size());
        assertEquals("NoticeVO vo", toDao.get(0).get("title"));
        assertEquals(List.of("title = request.title", "body = body"), toDao.get(0).get("lines"));
        assertEquals(2, ((List<String>) toDao.get(0).get("from")).size());
        // Biz 안에서 만든 VO
        List<Map<String, Object>> hist = vo(r, "noticeDAO.insertHistory(");
        assertEquals("NoticeVO history", hist.get(0).get("title"));
        assertEquals(List.of("title = vo.getTitle()", "body = \"written\""), hist.get(0).get("lines"));
    }
}
