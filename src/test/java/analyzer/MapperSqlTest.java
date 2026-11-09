package analyzer;

import analyzer.mermaid.FlowchartGenerator;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.SqlRef;
import analyzer.serializer.AnalysisReportWriter;
import analyzer.serializer.CallTreeWriter;
import analyzer.sql.MapperXmlIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 매퍼 SQL: 호출 → XML 파일·statement id·테이블 (쿼리 본문은 출력하지 않음) */
class MapperSqlTest {

    static SqlRef sql(AnalysisResult r, String expression) {
        return r.callSites.stream().filter(c -> c.expression.equals(expression)).findFirst().orElseThrow().sql;
    }

    @Test
    void tablesFromSql() {
        assertEquals(List.of("A", "B"), MapperXmlIndex.tables("select * from A a, B b where a.x = b.x"));
        assertEquals(List.of("A", "C"), MapperXmlIndex.tables("select * from A join C on 1=1 left outer join A x on 1=1"));
        // 서브쿼리 안 FROM 은 잡고, 함수 안 FROM(EXTRACT·TRIM)·문자열·주석·DUAL·WITH 이름은 뺀다
        assertEquals(List.of("T1", "T2"), MapperXmlIndex.tables(
                "with w as (select 1 from T1) select extract(year from d), trim(both ' ' from n), 'from X' "
                        + "from (select * from T2) s, w /* from C1 */ -- from C2\n where 1=1 and seq = (select 1 from dual)"));
        assertEquals(List.of("TB_ORDER"), MapperXmlIndex.tables("insert into TB_ORDER (A, B) values (#{a}, #{b})"));
        assertEquals(List.of("S.TB_USER"), MapperXmlIndex.tables("update S.TB_USER set N = #{n} where ID = #{id}"));
        assertEquals(List.of("T"), MapperXmlIndex.tables("select * from T where x = 1 for update"));
        assertEquals(List.of("TGT", "SRC"), MapperXmlIndex.tables(
                "merge into TGT t using SRC s on (t.id = s.id) when matched then update set t.v = s.v"));
        assertEquals(List.of("${table}"), MapperXmlIndex.tables("select * from ${table} where 1=1"));
        assertEquals(List.of("T"), MapperXmlIndex.tables("select seq.nextval from dual; insert into T (a) values (1)"));
    }

    /**
     * DAO 의 흔한 SQL 호출 방식 — SqlSession 타입이 아니어도 첫 인자가 매퍼 XML statement 와 맞으면 SQL 호출:
     * 상속 메서드(selectList("ns.id") / super.insert), 라이브러리 상위 클래스(EgovAbstractDAO.list), 공통 DAO 빈,
     * *DAO 이름의 MyBatis 인터페이스(XML namespace = FQN). XML 에 없으면 SQL 로 보지 않는다 (추측 금지).
     */
    @Test
    void daoPatternsAreSqlCallsOnlyWhenXmlHasTheStatement() {
        AnalysisResult r = Fixtures.analyze("DaoPatternAction", "execute", c -> c.analysis.expandRoles = List.of("DAO"));
        for (String[] e : new String[][]{
                {"selectList(\"notice.selectNotices\", vo)", "notice.selectNotices", "TB_NOTICE"},
                {"super.insert(\"notice.insertNotice\", vo)", "notice.insertNotice", "TB_NOTICE"},
                {"list(\"article.selectArticles\", vo)", "article.selectArticles", "TB_ARTICLE"},
                {"commonDao.selectList(\"notice.countNotices\", vo)", "notice.countNotices", "TB_NOTICE"},
                {"memberDAO.selectMember(memberId)", "com.example.dao.MemberDAO.selectMember", "TB_MEMBER"},
                // 문자열 검색: 메서드 이름 무관 · 지역 변수 선언 값(상수 연결) · DAO 안 namespace 없는 id
                {"commonQuery(\"notice.countNotices\", vo)", "notice.countNotices", "TB_NOTICE"},
                {"selectList(id, vo)", "notice.selectNotices", "TB_NOTICE"},
                {"insert(\"insertNotice\", vo)", "insertNotice", "TB_NOTICE"}}) {
            SqlRef ref = sql(r, e[0]);
            assertNotNull(ref, e[0]);
            assertEquals("FOUND", ref.xmlStatus, e[0]);
            assertEquals(e[1], ref.statementId, e[0]);
            assertTrue(ref.tables.contains(e[2]), e[0] + " " + ref.tables);
        }
        // XML 에 없는 id·메서드는 SQL 호출이 아니다
        assertNull(sql(r, "selectList(\"notInXml\", null)"));
        assertNull(sql(r, "memberDAO.notInXml(memberId)"));
        // 공통 DAO 안의 sqlSession.selectList(statementId, …) 는 id 미확정 + 호출하는 쪽 안내
        SqlRef inner = r.callSites.stream().filter(c -> c.expression.equals("sqlSession.selectList(statementId, param)")
                && r.method(c.callerMethodId).name.equals("selectList") && r.classOf(r.method(c.callerMethodId)).name.equals("CommonDAO"))
                .findFirst().orElseThrow().sql;
        assertEquals("SKIPPED", inner.xmlStatus);
        assertTrue(inner.notes.stream().anyMatch(n -> n.contains("notice.countNotices")), inner.notes.toString());
        // 분석서 2.3: XML id 를 못 찾은 DAO 메서드만 (공통 메서드·위임 메서드는 제외)
        String md = new analyzer.serializer.AnalysisReportWriter().render(r, "SKIPPED");
        String missing = md.substring(md.indexOf("### 2.3"), md.indexOf("## 3."));
        assertTrue(missing.contains("`NoticeDAO.notSql`"), missing);
        for (String ok : List.of("findNotices", "delegate", "BaseDAO.selectList", "CommonDAO.selectList", "count`", "byVariable", "bare")) {
            assertFalse(missing.contains(ok), ok + "\n" + missing);
        }
    }

    @Test
    void mapperAndSqlSessionCallsFindXmlIdAndTables() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "sqlCalls", c -> c.analysis.expandRoles = List.of("DAO"));
        // 매퍼 인터페이스: namespace = 인터페이스 FQN, id = 메서드명. include·WITH·서브쿼리 포함
        SqlRef user = sql(r, "userMapper.selectUser(userId)");
        assertEquals("MAPPER_INTERFACE", user.basis);
        assertEquals("FOUND", user.xmlStatus);
        assertEquals("src/main/resources/mapper/UserMapper.xml", user.xmlFile);
        assertEquals("com.example.mapper.UserMapper", user.namespace);
        assertEquals("selectUser", user.id);
        assertEquals("select", user.statementType);
        assertEquals(List.of("TB_LOGIN_HIST", "TB_ORDER", "TB_USER", "TB_ROLE"), user.tables);
        SqlRef upd = sql(r, "userMapper.updateUserName(userId)");
        assertEquals(List.of("TB_USER", "${nameTable}"), upd.tables);
        assertTrue(upd.notes.stream().anyMatch(n -> n.contains("동적 테이블")));
        assertEquals("ANNOTATION", sql(r, "userMapper.countUsers()").xmlStatus);
        // SqlSession: 상수 + 리터럴 연결, 단순명 namespace, namespace 없는 id
        SqlRef sel = sql(r, "sqlSession.selectList(NAMESPACE + \"selectOrders\", userId)");
        assertEquals("CONSTANT", sel.basis);
        assertEquals("com.example.mapper.OrderMapper.selectOrders", sel.statementId);
        assertEquals(List.of("TB_ORDER", "TB_ORDER_DTL"), sel.tables);
        assertEquals("src/main/resources/sqlmap/OrderMapper.xml", sel.xmlFile);
        assertEquals(List.of("TB_ORDER"), sql(r, "sqlSession.insert(\"OrderMapper.insertOrder\", order)").tables);
        assertEquals("FOUND", sql(r, "sqlSession.delete(\"deleteOrder\", id)").xmlStatus);
        // 식이라 id 확정 불가 / XML 에 없는 id — 지어내지 않는다
        SqlRef dyn = sql(r, "sqlSession.update(statementOf(id), id)");
        assertEquals("UNRESOLVED", dyn.basis);
        assertEquals("SKIPPED", dyn.xmlStatus);
        SqlRef miss = sql(r, "sqlSession.selectOne(\"com.example.mapper.OrderMapper.noSuchId\")");
        assertEquals("NOT_FOUND", miss.xmlStatus);
        assertNull(miss.xmlFile);
        // 매퍼가 아닌 호출에는 없음
        assertNull(r.callSites.stream().filter(c -> c.expression.equals("orderDAO.missing()")).findFirst().orElseThrow().sql);
    }

    @Test
    void reportFlowchartAndTreeShowIdsWithoutQueryText() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "sqlCalls", c -> c.analysis.expandRoles = List.of("DAO"));
        String md = new AnalysisReportWriter().render(r, "SKIPPED");
        assertTrue(md.contains("## 2. Mapper SQL (XML id · 테이블)"), md);
        assertTrue(md.contains("| `com.example.mapper.OrderMapper` | `src/main/resources/sqlmap/OrderMapper.xml` | TB_ORDER, TB_ORDER_DTL |"), md);
        assertTrue(md.contains("`com.example.mapper.UserMapper.selectUser` | select | `src/main/resources/mapper/UserMapper.xml`"), md);
        assertFalse(md.contains("SELECT") || md.contains("o.USER_ID = #{userId}"), "쿼리 본문은 넣지 않는다");
        // 흐름도: 호출 노드 아래 SQL·테이블 줄, return 에 합쳐진 SqlSession 호출도 return 노드에
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("SQL com.example.mapper.UserMapper.selectUser · UserMapper.xml #lt;select#gt;"), mmd);
        CallSiteInfo sel = r.callSites.stream().filter(c -> c.expression.startsWith("sqlSession.selectList")).findFirst().orElseThrow();
        assertTrue(mmd.contains(sel.controlBlockId + "([\"return sqlSession.selectList(NAMESPACE + #quot;selectOrders#quot;, userId)<br/>"
                + "SQL com.example.mapper.OrderMapper.selectOrders · OrderMapper.xml #lt;select#gt;<br/>테이블 TB_ORDER, TB_ORDER_DTL"), mmd);
        String tree = new CallTreeWriter(true).render(r);
        assertTrue(tree.contains("⛁ SQL com.example.mapper.OrderMapper.deleteOrder · OrderMapper.xml <delete> · 테이블 TB_ORDER"), tree);
    }
}
