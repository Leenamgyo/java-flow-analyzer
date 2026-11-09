package com.example.dao;

import java.util.List;
import org.apache.ibatis.session.SqlSession;

/** 프로젝트 공통 상위 DAO — 하위 DAO 가 selectList("ns.id", p) 처럼 상속 메서드로 SQL 을 부른다 */
public abstract class BaseDAO {
    protected SqlSession sqlSession;

    protected List<Object> selectList(String statementId, Object param) {
        return sqlSession.selectList(statementId, param);
    }

    /** 이름이 SQL 동사가 아닌 공통 메서드 — 이름과 무관하게 문자열 인자로 찾는다 */
    protected Object commonQuery(String statementId, Object param) {
        return sqlSession.selectOne(statementId, param);
    }

    protected int insert(String statementId, Object param) {
        return sqlSession.insert(statementId, param);
    }
}
