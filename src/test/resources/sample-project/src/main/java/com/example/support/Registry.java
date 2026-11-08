package com.example.support;

/** 상태 변화 대상: static void (클래스 상태) */
public class Registry {
    public static void register(String key) {
        Holder.KEYS.add(key);
    }

    static class Holder {
        static final java.util.List<String> KEYS = new java.util.ArrayList<>();
    }
}
