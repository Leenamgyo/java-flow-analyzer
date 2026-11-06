package com.shop.common;

import javax.servlet.http.HttpServletRequest;
import org.apache.struts.action.Action;

public abstract class BaseAction extends Action {

    protected String currentUser(HttpServletRequest request) {
        Object user = request.getSession().getAttribute("LOGIN_USER");
        return user == null ? "anonymous" : user.toString();
    }

    protected void writeAccessLog(String userId, String action) {
    }
}
