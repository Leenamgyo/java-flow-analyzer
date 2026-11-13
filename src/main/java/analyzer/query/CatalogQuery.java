package analyzer.query;

import analyzer.catalog.CatalogKeys;
import analyzer.serializer.Excludes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

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
 * 카탈로그 질의 (사용자: 데이터로 쿼리 — 공통화, 콘솔로 보고 결과도 남김). 분석하지 않고 카탈로그만 읽으며, 두 카탈로그의 공통
 * 인터페이스({@link CatalogKeys}: key · uses)만으로 잇는다 — 문자열을 억지로 맞추지 않는다.
 * <ul>
 *   <li>run 카탈로그 {@code <catalog>/run/*.yml}: Action 진입 메서드(key) → 쓰는 메서드 key 들</li>
 *   <li>Util 기능 목록 {@code <catalog>/*.yml}: Util 메서드(key) → 쓰는 메서드 key 들 (클래스의 메서드 전부 = unused 기준)</li>
 * </ul>
 * 결과는 {@link Table} (열·행) + 기준 데이터 요약 {@link #basis()}.
 */
public final class CatalogQuery {

    /** 쓰는 곳(Action 진입 메서드 key) → 쓰이는 메서드 key */
    public record Use(String entryKey, String usedKey) {
        public String where() {
            return CatalogKeys.display(entryKey).replaceFirst("\\(.*", ""); // OrderAction.execute
        }
    }

    public record Table(List<String> columns, List<List<String>> rows) {
    }

    /** 이전 형식(키 없이 클래스 → 시그니처)으로 남은 항목 수 — 다시 run 하면 새 형식 */
    private int legacyEntries;
    private final List<Use> uses = new ArrayList<>();
    private final Set<String> entries = new TreeSet<>();
    /** run 카탈로그 파일: Action 클래스 FQN → 파일 */
    final Map<String, Path> runFiles = new TreeMap<>();
    /** Util 기능 목록: 클래스 FQN → 그 클래스 메서드 key 들 (removed 제외) */
    final Map<String, Set<String>> utilMethods = new TreeMap<>();
    final Map<String, Path> utilFiles = new TreeMap<>();
    /** Util 메서드 key → 쓰는 key (Util 기능 목록의 uses) */
    final Map<String, List<String>> utilUses = new TreeMap<>();

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static CatalogQuery load(Path catalogDir) throws IOException {
        CatalogQuery q = new CatalogQuery();
        for (Path f : yml(catalogDir.resolve("run"))) {
            Map<String, Object> doc = read(f);
            q.runFiles.put(String.valueOf(doc.get("class")), f);
            if (!(doc.get("entries") instanceof List<?> list)) {
                continue;
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) {
                    continue;
                }
                if (e.get("key") == null || !(e.get("uses") instanceof List<?> u)) {
                    q.legacyEntries++;
                    continue;
                }
                String key = String.valueOf(e.get("key"));
                q.entries.add(key);
                u.forEach(x -> q.uses.add(new Use(key, String.valueOf(x))));
            }
        }
        for (Path f : yml(catalogDir)) {
            Map<String, Object> doc = read(f);
            if (doc.get("class") == null || !(doc.get("methods") instanceof List<?> ms)) {
                continue;
            }
            String cls = String.valueOf(doc.get("class"));
            q.utilFiles.put(cls, f);
            Set<String> keys = q.utilMethods.computeIfAbsent(cls, k -> new TreeSet<>());
            for (Object o : ms) {
                if (o instanceof Map<?, ?> m && m.get("key") != null && !Boolean.TRUE.equals(m.get("removed"))) {
                    String key = String.valueOf(m.get("key"));
                    keys.add(key);
                    if (m.get("uses") instanceof List<?> u) {
                        q.utilUses.put(key, u.stream().map(String::valueOf).toList());
                    }
                }
            }
        }
        return q;
    }

    /** 결과가 어떤 데이터 기준인지 (사용자: 데이터가 완벽해야 — 빠진 게 있으면 coverage) */
    public String basis() {
        Set<String> actions = new TreeSet<>();
        entries.forEach(k -> actions.add(CatalogKeys.classOf(k)));
        return "기준: run 카탈로그 Action " + actions.size() + "개 · 진입 메서드 " + entries.size() + "개 · Util 기능 목록 "
                + utilMethods.size() + "개" + (legacyEntries > 0 ? " · 이전 형식 항목 " + legacyEntries + "개 제외(그 Action 을 다시 run)" : "");
    }

    public List<Use> uses() {
        return uses;
    }

    // ------------------------------------------------------------------ 질의

    /** who-uses 패턴: 맞는 메서드마다 쓰는 곳(Action.진입) + 그 메서드를 쓰는 Util 메서드 (Util 기능 목록 기준) */
    public Table whoUses(String pattern, Filters f) {
        Map<String, Set<String>> by = new TreeMap<>();
        for (Use u : uses) {
            if (matchMethod(u.usedKey, pattern) && f.accept(u)) {
                by.computeIfAbsent(u.usedKey, k -> new TreeSet<>()).add(u.where());
            }
        }
        Map<String, Set<String>> viaUtil = new TreeMap<>();
        utilUses.forEach((util, list) -> list.forEach(k -> {
            if (matchMethod(k, pattern)) {
                viaUtil.computeIfAbsent(k, x -> new TreeSet<>()).add(CatalogKeys.display(util).replaceFirst("\\(.*", ""));
                by.computeIfAbsent(k, x -> new TreeSet<>());
            }
        }));
        List<List<String>> rows = new ArrayList<>();
        by.forEach((k, w) -> rows.add(List.of(CatalogKeys.display(k), String.valueOf(w.size()), String.join(", ", w),
                String.join(", ", viaUtil.getOrDefault(k, Set.of())))));
        return new Table(List.of("메서드", "쓰는 Action 수", "쓰는 곳 (Action.진입)", "쓰는 Util 메서드"), rows);
    }

    /** uses 패턴: 맞는 Action(.진입)이 쓰는 메서드 */
    public Table usesOf(String pattern, Filters f) {
        Map<String, Set<String>> by = new TreeMap<>();
        for (Use u : uses) {
            if (matchEntry(u.entryKey, pattern) && f.accept(u)) {
                by.computeIfAbsent(u.usedKey, k -> new TreeSet<>()).add(u.where());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        by.forEach((k, w) -> rows.add(List.of(CatalogKeys.simple(CatalogKeys.classOf(k)), CatalogKeys.display(k).substring(
                CatalogKeys.simple(CatalogKeys.classOf(k)).length() + 1), String.join(", ", w))));
        return new Table(List.of("클래스", "메서드", "쓰는 곳"), rows);
    }

    /** common N: N 곳(Action.진입) 이상에서 쓰는 메서드, 많이 쓰는 순 (공통화 후보) */
    public Table common(int min, Filters f) {
        Map<String, Set<String>> by = new LinkedHashMap<>();
        for (Use u : uses) {
            if (f.accept(u)) {
                by.computeIfAbsent(u.usedKey, k -> new TreeSet<>()).add(u.where());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        by.entrySet().stream().filter(e -> e.getValue().size() >= min)
                .sorted((a, b) -> b.getValue().size() != a.getValue().size() ? b.getValue().size() - a.getValue().size()
                        : a.getKey().compareTo(b.getKey()))
                .forEach(e -> rows.add(List.of(CatalogKeys.display(e.getKey()), String.valueOf(e.getValue().size()),
                        String.join(", ", e.getValue()))));
        return new Table(List.of("메서드", "쓰는 곳 수", "쓰는 곳 (Action.진입)"), rows);
    }

    /** shared A B …: 지정한 Action(.진입) 패턴들이 모두 쓰는 메서드 */
    public Table shared(List<String> patterns, Filters f) {
        Map<String, Set<String>> hit = new TreeMap<>(), where = new TreeMap<>();
        for (Use u : uses) {
            if (!f.accept(u)) {
                continue;
            }
            for (String p : patterns) {
                if (matchEntry(u.entryKey, p)) {
                    hit.computeIfAbsent(u.usedKey, k -> new LinkedHashSet<>()).add(p);
                    where.computeIfAbsent(u.usedKey, k -> new TreeSet<>()).add(u.where());
                }
            }
        }
        List<List<String>> rows = new ArrayList<>();
        hit.forEach((k, ps) -> {
            if (ps.size() == patterns.size()) {
                rows.add(List.of(CatalogKeys.display(k), String.join(", ", where.get(k))));
            }
        });
        return new Table(List.of("메서드", "쓰는 곳"), rows);
    }

    /** unused 클래스패턴: Util 기능 목록의 메서드 중 run 카탈로그의 어느 Action 도 쓰지 않는 것 (기능 목록이 있는 클래스만) */
    public Table unused(String classPattern) {
        Set<String> used = new TreeSet<>();
        uses.forEach(u -> used.add(u.usedKey));
        List<List<String>> rows = new ArrayList<>();
        utilMethods.forEach((cls, keys) -> {
            if (!Excludes.matches(List.of(classPattern), List.of(CatalogKeys.simple(cls), cls))) {
                return;
            }
            keys.stream().filter(k -> !used.contains(k)).forEach(k -> rows.add(List.of(CatalogKeys.simple(cls), CatalogKeys.display(k))));
        });
        return new Table(List.of("클래스", "어느 Action 도 안 쓰는 메서드"), rows);
    }

    /** usage: 클래스·메서드마다 쓰는 곳 전부 (역색인 전체) */
    public Table usage(Filters f) {
        Map<String, Set<String>> by = new TreeMap<>();
        for (Use u : uses) {
            if (f.accept(u)) {
                by.computeIfAbsent(u.usedKey, k -> new TreeSet<>()).add(u.where());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        by.forEach((k, w) -> rows.add(List.of(CatalogKeys.simple(CatalogKeys.classOf(k)), CatalogKeys.display(k),
                String.valueOf(w.size()), String.join(", ", w))));
        return new Table(List.of("클래스", "메서드", "쓰는 곳 수", "쓰는 곳 (Action.진입)"), rows);
    }

    // ------------------------------------------------------------------ 필터·유틸

    /** class=패턴(쓰이는 메서드의 클래스), action=패턴(쓰는 Action) */
    public record Filters(String classPattern, String actionPattern) {
        public static final Filters NONE = new Filters(null, null);

        boolean accept(Use u) {
            String cls = CatalogKeys.classOf(u.usedKey);
            return (classPattern == null || Excludes.matches(List.of(classPattern), List.of(CatalogKeys.simple(cls), cls)))
                    && (actionPattern == null || matchEntry(u.entryKey, actionPattern));
        }
    }

    /** 메서드 패턴: 클래스.메서드(단순명·FQN, 시그니처 포함 가능) · 클래스 · * */
    static boolean matchMethod(String key, String pattern) {
        String cls = CatalogKeys.classOf(key), sig = key.substring(key.indexOf('#') + 1), name = sig.replaceFirst("\\(.*", "");
        String simple = CatalogKeys.simple(cls);
        return Excludes.matches(List.of(pattern), List.of(simple + "." + name, cls + "." + name, simple + "." + sig,
                cls + "." + sig, simple, cls));
    }

    /** Action 패턴: Action · Action.진입메서드 (단순명·FQN) */
    static boolean matchEntry(String key, String pattern) {
        String cls = CatalogKeys.classOf(key), name = key.substring(key.indexOf('#') + 1).replaceFirst("\\(.*", "");
        String simple = CatalogKeys.simple(cls);
        return Excludes.matches(List.of(pattern), List.of(simple, cls, simple + "." + name, cls + "." + name));
    }

    private static List<Path> yml(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".yml") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path f) throws IOException {
        Object o = YAML.readValue(f.toFile(), Object.class);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
