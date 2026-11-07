package com.shop.util;

public final class StringUtil {
    public static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
