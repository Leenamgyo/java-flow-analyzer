package com.example.util;

import javax.servlet.http.HttpServletRequest;

public final class StringUtil {
    public static boolean isNotEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    public static String getParam(HttpServletRequest req, String name) {
        return req.getParameter(name);
    }
}
