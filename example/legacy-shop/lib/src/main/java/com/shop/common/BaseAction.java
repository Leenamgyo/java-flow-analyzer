package com.shop.common;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.apache.struts.action.Action;
import com.shop.vo.MemberVO;

public abstract class BaseAction extends Action {

    protected String currentUser(HttpServletRequest request) {
        Object user = request.getSession().getAttribute("LOGIN_USER");
        return user == null ? "anonymous" : user.toString();
    }

    protected MemberVO checkLogin(HttpServletRequest request) throws OrderException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("LOGIN_MEMBER") == null) {
            throw new OrderException("E401", "login required");
        }
        return (MemberVO) session.getAttribute("LOGIN_MEMBER");
    }

    protected void writeAccessLog(String userId, String action) {
    }

    protected void writeErrorLog(String userId, String code) {
    }
}
