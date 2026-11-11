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

    /** 실행 순서: Biz 호출 전에 부른 헬퍼의 set 만 — 뒤에서 부르는 헬퍼·Biz 안의 set 은 아직 안 일어남 */
    @Test
    void onlySetsBeforeTheCallInExecutionOrder() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "bizVoOrder",
                c -> c.analysis.expandRoles = List.of("Service", "Biz", "DAO", "Repository", "Mapper"));
        List<Map<String, Object>> toBiz = vo(r, "noticeWriteBiz.write(");
        assertEquals(1, toBiz.size(), toBiz.toString());
        assertEquals(List.of("title = request.t"), toBiz.get(0).get("lines"));
    }

    /** 안에서 DAO 를 부르면 '⛁ DB 로직' 줄 (호출 노드·메서드 시작 노드), DAO 를 직접 부르는 호출엔 없음 */
    @Test
    void dbLogicIsMarked() {
        AnalysisResult r = analyze();
        MermaidGraph g = new FlowchartGenerator().checked(r);
        CallSiteInfo write = r.callSites.stream().filter(c -> c.expression.startsWith("noticeWriteBiz.write(")).findFirst().orElseThrow();
        CallSiteInfo insert = r.callSites.stream().filter(c -> c.expression.startsWith("noticeDAO.insert(")).findFirst().orElseThrow();
        List<String> writeLines = g.nodeById(g.nodeOf(write.id)).lines().stream().map(MermaidGraph::plain).toList();
        assertTrue(writeLines.contains("⛁ DB 로직: NoticeDAO.insert, NoticeDAO.insertHistory"), writeLines.toString());
        String biz = r.methods().stream().filter(m -> m.name.equals("write")).findFirst().orElseThrow().id;
        assertTrue(g.nodeById(biz).lines().contains("⛁ DB 로직: NoticeDAO.insert, NoticeDAO.insertHistory"), g.nodeById(biz).lines().toString());
        assertFalse(g.nodeById(g.nodeOf(insert.id)).lines().stream().anyMatch(l -> l.contains("DB 로직")));
        // 태그: 호출 줄 끝·메서드 상자 제목 끝 [⛁ DB] (렌더링에서 이 글자만 빨강)
        assertTrue(writeLines.get(0).endsWith("  " + MermaidGraph.DB_TAG), writeLines.get(0));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("NoticeWriteBiz.write() «Biz» <font color='#b91c1c'>[⛁ DB]</font>"), mmd);
        assertFalse(g.nodeById(g.nodeOf(insert.id)).lines().get(0).contains(MermaidGraph.DB_TAG));
    }
}
