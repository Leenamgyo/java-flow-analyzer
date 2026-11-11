package analyzer.classflow;

import analyzer.model.AnalysisResult;
import analyzer.model.MethodInfo;
import analyzer.serializer.Labels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 카탈로그 적용 (runClass --apply, 사용자: 카탈로그가 업데이트되면 적용하는 apply): catalog/&lt;클래스&gt;.yml 의 최상위
 * {@code hidden:} 목록(사람이 손으로 관리)을 전체 클래스 흐름도에 처음부터 숨긴 메서드 상자로. 항목은 {@code 클래스.메서드}
 * (Labels.methodName — 중첩 클래스는 바깥 포함), {@code 클래스}(그 클래스 메서드 전부), {@code *} 패턴.
 */
public final class CatalogApply {

    private CatalogApply() {
    }

    /** 카탈로그의 hidden 목록 (파일·키가 없으면 빈 목록) */
    @SuppressWarnings("unchecked")
    public static List<String> hiddenPatterns(Path catalog) throws IOException {
        if (!Files.isRegularFile(catalog)) {
            return List.of();
        }
        Object doc = new ObjectMapper(new YAMLFactory()).readValue(catalog.toFile(), Object.class);
        if (doc instanceof Map<?, ?> m && m.get("hidden") instanceof List<?> list) {
            return list.stream().filter(x -> x != null).map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).toList();
        }
        return List.of();
    }

    /** 패턴에 맞는, 상자로 그리는(본문을 분석한) 메서드 ID */
    public static List<String> matchMethods(AnalysisResult r, List<String> patterns) {
        List<String> out = new ArrayList<>();
        for (MethodInfo m : r.methods()) {
            if (m.analyzed && patterns.stream().anyMatch(p -> matches(r, m, p))) {
                out.add(m.id);
            }
        }
        return out;
    }

    /** 아무 메서드에도 맞지 않는 패턴 (오타 확인용) */
    public static List<String> unmatched(AnalysisResult r, List<String> patterns) {
        return patterns.stream().filter(p -> r.methods().stream().noneMatch(m -> m.analyzed && matches(r, m, p))).toList();
    }

    static boolean matches(AnalysisResult r, MethodInfo m, String pattern) {
        String method = Labels.methodName(r, m);          // ProductInfoBizImpl.getInfo
        String cls = r.classOf(m).name;                   // ProductInfoBizImpl
        String fqn = r.classOf(m).qualifiedName + "." + m.name;
        Pattern p = Pattern.compile(java.util.Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b).orElse(""));
        return p.matcher(method).matches() || p.matcher(cls).matches() || p.matcher(fqn).matches()
                || p.matcher(r.classOf(m).qualifiedName).matches();
    }
}
