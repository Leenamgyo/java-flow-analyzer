package com.shop.util;

import javax.servlet.http.HttpServletRequest;

public final class ParamUtil {
    public static int getInt(HttpServletRequest req, String name, int def) {
        String v = req.getParameter(name);
        return v == null ? def : Integer.parseInt(v);
    }

    public static String getString(HttpServletRequest req, String name, String def) {
        String v = req.getParameter(name);
        return v == null ? def : v;
    }
}
