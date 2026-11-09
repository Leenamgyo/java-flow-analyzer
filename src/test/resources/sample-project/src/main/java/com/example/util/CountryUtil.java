package com.example.util;

/** 로직이 많은 Util — 국가 코드 결정 */
public final class CountryUtil {
    public static String resolveCountry(String mcc, String lang) {
        if (mcc == null || mcc.isEmpty()) {
            return byLang(lang);
        }
        switch (mcc) {
            case "450":
                return "KR";
            case "440":
                return "JP";
            default:
                return byLang(lang);
        }
    }

    static String byLang(String lang) {
        if ("ja".equals(lang)) {
            return "JP";
        }
        return "KR";
    }
}
