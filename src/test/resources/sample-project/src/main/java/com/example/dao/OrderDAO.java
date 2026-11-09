package com.example.dao;

import java.util.List;
import org.apache.ibatis.session.SqlSession;

public class OrderDAO {
    private static final String NAMESPACE = "com.example.mapper.OrderMapper.";
    private SqlSession sqlSession;

    public List<Object> selectOrders(String userId) {
        return sqlSession.selectList(NAMESPACE + "selectOrders", userId);
    }

    public int insertOrder(Object order) {
        return sqlSession.insert("OrderMapper.insertOrder", order);
    }

    public void deleteOrder(String id) {
        sqlSession.delete("deleteOrder", id);
    }

    public void dynamic(String id) {
        sqlSession.update(statementOf(id), id);
    }

    public void missing() {
        sqlSession.selectOne("com.example.mapper.OrderMapper.noSuchId");
    }

    private String statementOf(String id) {
        return NAMESPACE + id;
    }
}
