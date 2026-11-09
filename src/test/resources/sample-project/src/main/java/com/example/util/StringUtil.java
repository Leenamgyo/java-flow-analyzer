package com.example.util;

import javax.servlet.http.HttpServletRequest;

public final class StringUtil {
    public static String defVal(String value, String def) {
        return value == null || value.isEmpty() ? def : value;
    }

    public static boolean isNotEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    public static String getParam(HttpServletRequest req, String name) {
        return req.getParameter(name);
    }

    public static String getParameter(HttpServletRequest req) {
        return req.getParameter("lang");
    }

    public static String getParameter(HttpServletRequest req, String name) {
        return req.getParameter(name);
    }

    public static String getParameter(HttpServletRequest req, String name, String def) {
        String v = req.getParameter(name);
        return v == null ? def : v;
    }
}
