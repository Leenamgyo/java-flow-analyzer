package com.example.util;

public final class ODSUtil {
    public static boolean isQAStore(String shopId) {
        return shopId != null && shopId.startsWith("QA");
    }
}
