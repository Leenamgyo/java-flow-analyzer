package com.example.dao;

import java.util.List;

/** 상속 메서드로 SQL 호출 (수신 객체 없음 / super.) */
public class NoticeDAO extends BaseDAO {

    public List<Object> findNotices(Object vo) {
        return selectList("notice.selectNotices", vo);
    }

    public void addNotice(Object vo) {
        super.insert("notice.insertNotice", vo);
    }

    private static final String NS = "notice.";

    /** 메서드 이름 무관 (commonQuery) */
    public Object count(Object vo) {
        return commonQuery("notice.countNotices", vo);
    }

    /** 지역 변수에 담은 id (상수 연결) */
    public List<Object> byVariable(Object vo) {
        String id = NS + "selectNotices";
        return selectList(id, vo);
    }

    /** namespace 없는 id (iBatis) — DAO 안이라 허용 */
    public void bare(Object vo) {
        insert("insertNotice", vo);
    }

    /** 다른 DAO 메서드에 위임 — 못 찾음 아님 */
    public List<Object> delegate(Object vo) {
        return findNotices(vo);
    }

    /** XML 에 없는 id — SQL 호출로 보지 않는다 (추측 금지), 분석서 2.3 못 찾음에 나온다 */
    public List<Object> notSql() {
        return selectList("notInXml", null);
    }
}
