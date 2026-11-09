package com.example.action;

import com.example.dao.MemberDAO;

/** MyBatis 매퍼 인터페이스를 *DAO 로 이름 붙인 경우 — XML namespace = 인터페이스 FQN 이면 SQL 호출 */
public class DaoPatternAction {
    private MemberDAO memberDAO;

    public String execute(String memberId) {
        Object m = memberDAO.selectMember(memberId);
        memberDAO.notInXml(memberId);
        return String.valueOf(m);
    }
}
