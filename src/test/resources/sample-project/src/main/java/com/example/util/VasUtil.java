package com.example.util;

public final class VasUtil {
    public static String getExceptionScVersion(String appId) {
        return appId == null ? "0" : "1." + appId;
    }

    public static String normalize(String v) {
        return v == null ? "" : v.replace('_', '.');
    }
}
