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

    /** XML 에 없는 id — SQL 호출로 보지 않는다 (추측 금지) */
    public List<Object> notSql() {
        return selectList("notInXml", null);
    }
}
