package com.shop.util;

import javax.servlet.http.HttpServletRequest;

public final class ParamUtil {
    public static int getInt(HttpServletRequest req, String name, int def) {
        String v = req.getParameter(name);
        return v == null ? def : Integer.parseInt(v);
    }

    public static long getLong(HttpServletRequest req, String name, long def) {
        String v = req.getParameter(name);
        return v == null ? def : Long.parseLong(v);
    }

    public static boolean getBoolean(HttpServletRequest req, String name, boolean def) {
        String v = req.getParameter(name);
        return v == null ? def : "Y".equals(v) || "true".equals(v);
    }

    public static String getString(HttpServletRequest req, String name, String def) {
        String v = req.getParameter(name);
        return v == null ? def : v;
    }

    /** yyyy-MM-dd 형식만 허용 */
    public static String getDate(HttpServletRequest req, String name, String def) {
        String v = req.getParameter(name);
        return v == null || !v.matches("\\d{4}-\\d{2}-\\d{2}") ? def : v;
    }
}
