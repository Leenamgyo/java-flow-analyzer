package com.shop.util;

public final class DateUtils {
    public static String today() {
        return java.time.LocalDate.now().toString();
    }

    public static String now() {
        return java.time.LocalDateTime.now().toString();
    }

    public static java.time.LocalDate parse(String s) {
        return java.time.LocalDate.parse(s);
    }

    public static String mask(String s) {
        return s.length() < 4 ? "****" : s.substring(0, s.length() - 4) + "****";
    }
}
