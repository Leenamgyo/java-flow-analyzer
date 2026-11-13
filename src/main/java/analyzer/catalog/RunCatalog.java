package analyzer.catalog;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.MethodInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * run 카탈로그 + 사용처 역색인 (사용자: 공통화를 진행할 건데 각 Action 이 어떤 메서드를 쓰는지, 그리고 이 클래스의 메서드는 어디서
 * 쓰는지가 필요). Action 분석(run) 을 할 때마다 {@code <catalog-dir>/run/<Action>.yml} 에 그 진입 메서드에서 (전이적으로) 닿는
 * 프로젝트 소스 메서드 전부를 클래스별로 누적한다 — 같은 Action 의 다른 진입 메서드 항목은 건드리지 않고, note·직접 추가한 키는 보존.
 * 형식은 Util 카탈로그와 같은 공통 인터페이스({@link CatalogKeys}: key · uses). 질의는 {@code ./gradlew query} ({@code analyzer.query}).
 */
public final class RunCatalog {

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());

    private RunCatalog() {
    }

    public static Path dir(AnalyzerConfig cfg) {
        return Path.of(cfg.output.catalogDir).toAbsolutePath().normalize().resolve("run");
    }

    /** 이 분석(진입 메서드 하나)의 사용 메서드를 run/<Action>.yml 에 누적. @return 쓴 파일 */
    public static Path write(AnalysisResult r, AnalyzerConfig cfg) throws IOException {
        MethodInfo entry = r.method(r.entryMethodId);
        var target = r.clazz(r.targetClassId);
        Path file = UtilCatalog.catalogFile(cfg, "run/" + target.name);
        Map<String, Object> fresh = new LinkedHashMap<>();
        fresh.put("key", entry.key);                 // 공통 인터페이스 (Util 카탈로그·쿼리와 같은 키)
        fresh.put("method", declaration(entry));
        fresh.put("uses", CatalogKeys.uses(r, cfg)); // 흐름도에 그려진 메서드 key 전부 (흐름도 노드 그대로)
        Map<String, Object> old = read(file);
        Files.createDirectories(file.getParent());
        Files.writeString(file, YAML.writeValueAsString(merge(target.qualifiedName, fresh, old)));
        return file;
    }

    /** 진입 메서드 표시: execute(HttpServletRequest request, …) — 파라미터 이름을 모르면 타입만 */
    static String declaration(MethodInfo m) {
        if (m.parameters.isEmpty() || m.parameters.stream().anyMatch(p -> p.name() == null)) {
            return m.signature;
        }
        return m.name + "(" + String.join(", ", m.parameters.stream().map(p -> p.type() + " " + p.name()).toList()) + ")";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Object o = YAML.readValue(file.toFile(), Object.class);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 같은 진입 메서드 항목만 바꾸고(note·직접 추가한 키 보존), 다른 진입 메서드 항목과 최상위 추가 키는 그대로 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(String fqn, Map<String, Object> fresh, Map<String, Object> old) {
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean replaced = false;
        if (old.get("entries") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> e = (Map<String, Object>) m;
                if (fresh.get("key").equals(e.get("key")) || e.get("key") == null && fresh.get("method").equals(e.get("method"))) {
                    Map<String, Object> out = new LinkedHashMap<>(fresh);
                    out.put("note", e.get("note") == null ? "" : e.get("note"));
                    e.forEach((k, v) -> out.putIfAbsent(k, v)); // 사람이 추가한 키 (key·method·uses 는 새 값)
                    entries.add(out);
                    replaced = true;
                } else {
                    entries.add(e);
                }
            }
        }
        if (!replaced) {
            Map<String, Object> out = new LinkedHashMap<>(fresh);
            out.put("note", "");
            entries.add(out);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("class", fqn);
        doc.put("entries", entries);
        old.forEach((k, v) -> doc.putIfAbsent(k, v));
        return doc;
    }

}
