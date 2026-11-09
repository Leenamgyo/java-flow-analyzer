package com.example.action;

import com.example.dao.ArticleDAO;
import com.example.dao.CommonDAO;
import com.example.dao.MemberDAO;
import com.example.dao.NoticeDAO;

/** DAO 의 여러 SQL 호출 방식: 상속 메서드·라이브러리 상위 클래스·공통 DAO 빈·*DAO 이름의 매퍼 인터페이스 */
public class DaoPatternAction {
    private NoticeDAO noticeDAO;
    private ArticleDAO articleDAO;
    private CommonDAO commonDao;
    private MemberDAO memberDAO;

    public String execute(Object vo, String memberId) {
        noticeDAO.findNotices(vo);
        noticeDAO.addNotice(vo);
        noticeDAO.notSql();
        noticeDAO.count(vo);
        noticeDAO.byVariable(vo);
        noticeDAO.bare(vo);
        noticeDAO.delegate(vo);
        articleDAO.findArticles(vo);
        commonDao.selectList("notice.countNotices", vo);
        Object m = memberDAO.selectMember(memberId);
        memberDAO.notInXml(memberId);
        return String.valueOf(m);
    }
}
