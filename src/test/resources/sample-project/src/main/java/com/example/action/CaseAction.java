package com.example.action;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import com.example.exception.BizException;
import com.example.vendor.*;

/** 테스트 케이스별 진입 메서드 모음 */
public class CaseAction {

    // ---- 오버로딩
    public void overload() {
        format("a");
        format("a", 3);
    }

    private String format(String v) {
        return v;
    }

    private String format(String v, int width) {
        return v + width;
    }

    // ---- 진입 메서드 모호성
    public void process(String s) {
    }

    public void process(int n) {
    }

    // ---- 중복 호출
    public void duplicate() {
        helper();
        helper();
        wrapper();
    }

    private void helper() {
    }

    private void wrapper() {
        helper();
    }

    // ---- 재귀 / 상호 재귀
    public int recursive(int n) {
        if (n <= 0) {
            return 0;
        }
        return recursive(n - 1);
    }

    public void mutualA() {
        mutualB();
    }

    private void mutualB() {
        mutualA();
    }

    // ---- try-with-resources + 중첩 try
    public String tryWithResources(String text) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text));
             BufferedReader second = new BufferedReader(new StringReader(text))) {
            try {
                return reader.readLine();
            } catch (IOException inner) {
                logError(inner);
                return null;
            }
        } catch (IOException e) {
            logError(e);
            return null;
        }
    }

    // ---- return 과 finally / finally 안의 return
    public int returnWithFinally() {
        try {
            compute();
            return 1;
        } finally {
            release();
        }
    }

    @SuppressWarnings("finally")
    public int finallyOverridesReturn() {
        try {
            throw new IllegalStateException("x");
        } finally {
            return 2;
        }
    }

    // ---- 예외 매칭
    public void throwCases(int k) throws BizException {
        try {
            if (k == 1) {
                throw new IllegalArgumentException("a");
            }
            if (k == 2) {
                throw new IllegalStateException("b");
            }
            if (k == 3) {
                throw new BizException("c");
            }
        } catch (IllegalArgumentException e) {
            logError(e);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // ---- switch / loop / 삼항 / 단락평가 / 람다
    public void switchAndLoop(int code, List<String> items) {
        switch (code) {
            case 1:
                compute();
                break;
            case 2, 3:
                release();
                break;
            default:
                logError(null);
        }
        for (String item : items) {
            if (item.isEmpty()) {
                continue;
            }
            compute();
        }
        int v = code > 0 ? compute() : 0;
        boolean ok = code > 1 && check();
        items.forEach(x -> release());
    }

    // ---- 미해결 / 부분 해결
    public void unresolvedCalls() {
        var client = SdkClient.getInstance();
        client.push("x");
        SdkClient.getInstance().push("y");
        undeclared.call();
        if ("lit".equals(SdkClient.getInstance().name())) {
            release();
        }
    }

    private int compute() {
        return 1;
    }

    private boolean check() {
        return true;
    }

    private void release() {
    }

    private void logError(Exception e) {
    }
}
