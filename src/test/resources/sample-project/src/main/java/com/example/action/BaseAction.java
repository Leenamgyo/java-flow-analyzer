package com.example.action;

import javax.servlet.http.HttpServletRequest;

public abstract class BaseAction {

    protected static final String PARAM_LOCALE = "locale";

    protected String getLoginUserId(HttpServletRequest request) {
        return String.valueOf(request.getSession().getAttribute("userId"));
    }
}
