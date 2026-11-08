package com.example.util;

import javax.servlet.http.HttpServletRequest;

public final class StringUtil {
    public static String getParam(HttpServletRequest req, String name) {
        return req.getParameter(name);
    }
}
