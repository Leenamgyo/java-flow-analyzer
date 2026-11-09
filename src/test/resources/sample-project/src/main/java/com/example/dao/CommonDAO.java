package com.example.dao;

import java.util.List;
import org.apache.ibatis.session.SqlSession;

/** 공통 DAO 빈 — 다른 클래스가 commonDao.selectList("ns.id", p) 로 부른다 */
public class CommonDAO {
    private SqlSession sqlSession;

    public List<Object> selectList(String statementId, Object param) {
        return sqlSession.selectList(statementId, param);
    }
}
