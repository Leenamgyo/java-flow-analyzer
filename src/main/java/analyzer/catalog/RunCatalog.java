package analyzer.catalog;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import analyzer.serializer.Excludes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * run 카탈로그 + 사용처 역색인 (사용자: 공통화를 진행할 건데 각 Action 이 어떤 메서드를 쓰는지, 그리고 이 클래스의 메서드는 어디서
 * 쓰는지가 필요). Action 분석(run) 을 할 때마다 {@code <catalog-dir>/run/<Action>.yml} 에 그 진입 메서드에서 (전이적으로) 닿는
 * 프로젝트 소스 메서드 전부를 클래스별로 누적한다 — 같은 Action 의 다른 진입 메서드 항목은 건드리지 않고, note·직접 추가한 키는 보존.
 * 역색인은 이 파일들만 읽는다(소스를 다시 분석하지 않음): {@code --usage} → usage.md, {@code --who-uses 패턴} → 콘솔.
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
        fresh.put("method", declaration(entry));
        fresh.put("uses", uses(r));
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

    /** 진입에서 닿는 프로젝트 소스 메서드 (호출 대상·구현 후보, 진입 메서드 자신 제외): 클래스 FQN → 시그니처들 (정렬) */
    static Map<String, List<String>> uses(AnalysisResult r) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (CallSiteInfo s : r.callSites) {
            for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
                if (t == null || r.isEntry(t)) {
                    continue;
                }
                MethodInfo m = r.method(t);
                if (m.origin != Origin.SOURCE) {
                    continue;
                }
                out.computeIfAbsent(r.classOf(m).qualifiedName, k -> new TreeSet<>()).add(m.signature);
            }
        }
        Map<String, List<String>> list = new LinkedHashMap<>();
        out.forEach((k, v) -> list.put(k, List.copyOf(v)));
        return list;
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
                if (fresh.get("method").equals(e.get("method"))) {
                    Map<String, Object> out = new LinkedHashMap<>(fresh);
                    out.put("note", e.get("note") == null ? "" : e.get("note"));
                    e.forEach((k, v) -> out.putIfAbsent(k, v)); // 사람이 추가한 키
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

    // ------------------------------------------------------------------ 사용처 역색인

    /** 클래스 FQN → 메서드 시그니처 → 쓰는 곳(Action.진입메서드) — run 카탈로그 전부에서 */
    public static Map<String, Map<String, Set<String>>> usage(AnalyzerConfig cfg) throws IOException {
        Map<String, Map<String, Set<String>>> out = new TreeMap<>();
        Path dir = dir(cfg);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        }
        for (Path f : files) {
            Map<String, Object> doc = read(f);
            String action = simple(String.valueOf(doc.get("class")));
            if (!(doc.get("entries") instanceof List<?> entries)) {
                continue;
            }
            for (Object o : entries) {
                if (!(o instanceof Map<?, ?> e) || !(e.get("uses") instanceof Map<?, ?> uses)) {
                    continue;
                }
                String where = action + "." + String.valueOf(e.get("method")).replaceFirst("\\(.*", "");
                uses.forEach((cls, ms) -> {
                    if (ms instanceof List<?> l) {
                        for (Object m : l) {
                            out.computeIfAbsent(String.valueOf(cls), k -> new TreeMap<>())
                                    .computeIfAbsent(String.valueOf(m), k -> new TreeSet<>()).add(where);
                        }
                    }
                });
            }
        }
        return out;
    }

    /** usage.md: 클래스마다 메서드 · 쓰는 Action 수 · Action 목록 (공통화 대상 찾기 — 여러 Action 이 쓰는 메서드가 위로) */
    public static Path writeUsage(AnalyzerConfig cfg) throws IOException {
        Map<String, Map<String, Set<String>>> u = usage(cfg);
        Set<String> actions = new TreeSet<>();
        u.values().forEach(ms -> ms.values().forEach(actions::addAll));
        StringBuilder sb = new StringBuilder("# 메서드 사용처 (Action 기준)\n\n");
        sb.append("`run` 할 때 쌓인 `").append(cfg.output.catalogDir).append("/run/*.yml` 기준 — 진입 메서드 ")
                .append(actions.size()).append("개. 다시 만들려면 `--usage`.\n\n");
        if (u.isEmpty()) {
            sb.append("run 카탈로그가 없습니다. Action 을 `./gradlew run` 으로 분석하면 쌓입니다.\n");
        }
        for (var c : u.entrySet()) {
            sb.append("## ").append(simple(c.getKey())).append("\n\n`").append(c.getKey()).append("`\n\n");
            sb.append("| 메서드 | 쓰는 곳 수 | 쓰는 곳 (Action.진입 메서드) |\n|---|---|---|\n");
            c.getValue().entrySet().stream()
                    .sorted((a, b) -> b.getValue().size() != a.getValue().size() ? b.getValue().size() - a.getValue().size()
                            : a.getKey().compareTo(b.getKey()))
                    .forEach(m -> sb.append("| ").append(md(m.getKey())).append(" | ").append(m.getValue().size()).append(" | ")
                            .append(md(String.join(", ", m.getValue()))).append(" |\n"));
            sb.append('\n');
        }
        Path file = Path.of(cfg.output.catalogDir).toAbsolutePath().normalize().resolve("usage.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString());
        return file;
    }

    /** 패턴(클래스.메서드 — 단순명·FQN, * 가능)에 맞는 메서드와 쓰는 곳 */
    public static Map<String, Set<String>> whoUses(AnalyzerConfig cfg, String pattern) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (var c : usage(cfg).entrySet()) {
            for (var m : c.getValue().entrySet()) {
                String name = m.getKey().replaceFirst("\\(.*", "");
                List<String> names = List.of(simple(c.getKey()) + "." + name, c.getKey() + "." + name,
                        simple(c.getKey()) + "." + m.getKey(), simple(c.getKey()), c.getKey());
                if (Excludes.matches(List.of(pattern), names)) {
                    out.put(simple(c.getKey()) + "." + m.getKey(), new LinkedHashSet<>(m.getValue()));
                }
            }
        }
        return out;
    }

    private static String simple(String fqn) {
        return fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
    }

    private static String md(String s) {
        return s.replace("|", "\\|");
    }
}
