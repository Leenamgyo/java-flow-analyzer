package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.MethodInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 사용자 설정 exclude 패턴(* 와일드카드) — 흐름도(output.flowchart-exclude)·호출 트리(output.call-tree-exclude) 공용.
 * 호출 대상 "클래스.메서드"(단순명·FQN, 구현 후보 포함) 또는 호출 원문 "수신객체.메서드" 와 비교한다.
 */
public final class Excludes {
    private Excludes() {
    }

    public static boolean call(AnalysisResult r, List<String> patterns, CallSiteInfo s) {
        if (patterns.isEmpty()) {
            return false;
        }
        List<String> names = new ArrayList<>();
        for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
            if (t != null) {
                names.addAll(methodNames(r, r.method(t)));
            }
        }
        names.add(s.scopeExpression == null ? s.methodName : s.scopeExpression + "." + s.methodName);
        return matches(patterns, names);
    }

    public static boolean method(AnalysisResult r, List<String> patterns, MethodInfo m) {
        return !patterns.isEmpty() && matches(patterns, methodNames(r, m));
    }

    private static List<String> methodNames(AnalysisResult r, MethodInfo m) {
        var c = r.classOf(m);
        List<String> out = new ArrayList<>(List.of(c.name + "." + m.name));
        if (c.qualifiedName != null) {
            out.add(c.qualifiedName + "." + m.name);
        }
        return out;
    }

    private static boolean matches(List<String> patterns, List<String> names) {
        for (String p : patterns) {
            Pattern re = Pattern.compile(Arrays.stream(p.trim().split("\\*", -1)).map(Pattern::quote)
                    .collect(Collectors.joining(".*")));
            if (names.stream().anyMatch(n -> re.matcher(n).matches())) {
                return true;
            }
        }
        return false;
    }
}
