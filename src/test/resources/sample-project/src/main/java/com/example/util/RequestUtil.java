package com.example.util;

import javax.servlet.http.HttpServletRequest;

public final class RequestUtil {
    public static String getString(HttpServletRequest request, String name, String defaultValue) {
        String v = request.getParameter(name);
        return v == null ? defaultValue : v;
    }
}
