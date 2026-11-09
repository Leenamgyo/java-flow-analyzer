package com.example.dao;

import egovframework.rte.psl.dataaccess.EgovAbstractDAO;
import java.util.List;

/** 라이브러리(jar) 상위 클래스의 상속 메서드: list("ns.id", vo) */
public class ArticleDAO extends EgovAbstractDAO {

    public List<?> findArticles(Object vo) {
        return list("article.selectArticles", vo);
    }
}
