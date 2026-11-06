package com.example.util;

import javax.servlet.http.HttpServletRequest;

public final class ParameterUtil {
    public static int getInt(HttpServletRequest request, String name, int defaultValue) {
        String v = request.getParameter(name);
        return v == null ? defaultValue : Integer.parseInt(v);
    }
}
