package analyzer.model;

import java.util.Set;

/**
 * 로깅 호출 판정 (소스 원문 기준 — 메서드 이름 + 수신 객체 표현식).
 * <ul>
 *   <li>{@code LOG.error(…)} · {@code logger.debug(…)} · {@code getLogger().info(…)}: 레벨 메서드 + 수신 객체 이름에 "log"</li>
 *   <li>{@code System.out.println(…)} · {@code System.err.print*(…)}</li>
 *   <li>{@code e.printStackTrace()}</li>
 * </ul>
 * 흐름도의 로그 블록과, include-external-library-calls: false 여도 로깅 호출을 모델에 남기는 판정에 같이 쓴다.
 */
public final class LogCalls {

    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warn", "error", "fatal");

    private LogCalls() {
    }

    public static boolean isLog(String methodName, String scope) {
        if (methodName == null) {
            return false;
        }
        if (methodName.equals("printStackTrace")) {
            return true;
        }
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String s = scope.trim();
        if ((s.equals("System.out") || s.equals("System.err")) && methodName.startsWith("print")) {
            return true;
        }
        String last = s.substring(s.lastIndexOf('.') + 1).toLowerCase();
        return LEVELS.contains(methodName) && last.contains("log");
    }

    public static boolean isLog(CallSiteInfo s) {
        return isLog(s.methodName, s.scopeExpression);
    }
}
