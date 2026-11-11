package analyzer.catalog;

import analyzer.Main;
import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import analyzer.parser.EntryPointLocator;
import analyzer.parser.ProjectSources;
import analyzer.serializer.Labels;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Util 기능 목록 (CLI --all-methods, 설정 target.all-methods) — 사용자: ContentUtil 은 특정 메서드에서 내려가는 계층이 아니라
 * 유틸 전체의 기능 분석, 결과 파일은 빌드를 계속해도 남아 계속 관리되어야 하고 void 목록도 관리.
 * <p>
 * 클래스에 선언된 본문 있는 메서드마다 기존 분석(Main.analyze — 같은 규칙: 빈 이름·리플렉션·DAO·SQL 추적)을 그 메서드를 진입으로
 * 돌려, 메서드 하나 = 기능 하나로 의존을 정리한다. 결과는 output/ 가 아니라 계속 관리되는 {@code <catalog-dir>/<클래스>.yml}
 * (기본 catalog/, git 으로 관리 — 빌드는 건드리지 않음). 다시 돌리면 분석 칸은 새로 계산하고, 사람이 적은 칸(note 와 분석이 만들지
 * 않는 키)은 보존, 소스에서 없어진 메서드는 지우지 않고 removed: true. 시각·실행 정보는 넣지 않는다(변경이 없으면 파일도 같게).
 */
public final class UtilCatalog {

    /** 분석이 매번 새로 쓰는 칸 — 그 밖의 키(note 등)는 사람이 관리하는 칸으로 보존 */
    static final Set<String> GENERATED = Set.of("signature", "visibility", "static", "returns", "services", "daos", "sql",
            "uses-utils", "internal-calls", "beans", "reflection", "unresolved", "error", "removed");

    private static final Set<ClassRole> SERVICE = java.util.EnumSet.of(ClassRole.SERVICE, ClassRole.BIZ);
    private static final Set<ClassRole> DB = java.util.EnumSet.of(ClassRole.DAO, ClassRole.MAPPER, ClassRole.REPOSITORY);

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());

    private UtilCatalog() {
    }

    /** 목록을 만들거나 갱신한다. @return 쓴 파일 */
    public static Path run(AnalyzerConfig cfg) throws IOException {
        ProjectSources sources = new ProjectSources(cfg.target.effectiveSourceRoots().stream().map(Path::of).toList(),
                cfg.analysis.classpathJars.stream().map(Path::of).toList());
        TypeDeclaration<?> type = EntryPointLocator.locateType(sources, cfg.target.className);
        String fqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        Path file = catalogFile(cfg, type.getNameAsString());

        List<Map<String, Object>> fresh = new ArrayList<>();
        String savedMethod = cfg.target.entryMethod;
        List<String> savedTypes = cfg.target.entryMethodParameterTypes;
        String savedClass = cfg.target.className;
        try {
            cfg.target.className = fqn; // 같은 단순명이 여러 개여도 이 클래스로
            for (MethodDeclaration md : type.getMethods()) {
                if (md.getBody().isEmpty()) {
                    continue;
                }
                cfg.target.entryMethod = md.getNameAsString();
                cfg.target.entryMethodParameterTypes = md.getParameters().stream()
                        .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "")).toList();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("signature", signature(md));
                m.put("visibility", md.getAccessSpecifier().asString().isEmpty() ? "package-private" : md.getAccessSpecifier().asString());
                m.put("static", md.isStatic());
                m.put("returns", md.getType().asString());
                try {
                    describe(Main.analyze(cfg), fqn, m);
                } catch (RuntimeException e) {
                    m.put("error", e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString()));
                    System.err.println("[WARN] " + type.getNameAsString() + "." + m.get("signature") + " 분석 실패 — " + m.get("error"));
                }
                fresh.add(m);
            }
        } finally {
            cfg.target.className = savedClass;
            cfg.target.entryMethod = savedMethod;
            cfg.target.entryMethodParameterTypes = savedTypes;
        }
        Map<String, Object> doc = merge(fqn, fresh, read(file));
        Files.createDirectories(file.getParent());
        Files.writeString(file, YAML.writeValueAsString(doc));
        return file;
    }

    /** 목록 파일 위치: output.catalog-dir/<클래스 단순명>.yml — 원본 소스 안에는 쓰지 않는다 */
    public static Path catalogFile(AnalyzerConfig cfg, String simpleName) {
        Path dir = Path.of(cfg.output.catalogDir).toAbsolutePath().normalize();
        for (String root : cfg.target.effectiveSourceRoots()) {
            if (dir.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("catalog-dir 가 source-root 내부입니다. 원본 프로젝트에는 쓰지 않습니다: " + dir);
            }
        }
        return dir.resolve(simpleName + ".yml");
    }

    private static String signature(MethodDeclaration md) {
        return md.getNameAsString() + "(" + String.join(", ", md.getParameters().stream().map(UtilCatalog::param).toList()) + ")";
    }

    private static String param(Parameter p) {
        return p.getType().asString() + (p.isVarArgs() ? "..." : "") + " " + p.getNameAsString();
    }

    /** 그 메서드를 진입으로 분석한 결과(도달한 호출 전부)에서 기능 하나의 의존 */
    static void describe(AnalysisResult r, String classFqn, Map<String, Object> m) {
        Set<String> services = new LinkedHashSet<>();
        Set<String> daos = new LinkedHashSet<>();
        Map<String, Map<String, Object>> sql = new LinkedHashMap<>();
        Set<String> utils = new LinkedHashSet<>();
        Set<String> internal = new LinkedHashSet<>();
        Set<String> beans = new LinkedHashSet<>();
        Set<String> reflection = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (CallSiteInfo s : r.callSites) {
            if (s.targetMethodId != null) {
                MethodInfo declared = r.method(s.targetMethodId);
                MethodInfo impl = s.implementationMethodId == null ? null : r.method(s.implementationMethodId);
                ClassRole role = r.classOf(impl != null ? impl : declared).role;
                String name = Labels.methodName(r, declared) + (impl == null ? "" : " → " + Labels.methodName(r, impl));
                if (SERVICE.contains(role) || SERVICE.contains(r.classOf(declared).role)) {
                    services.add(name);
                } else if (DB.contains(role) || DB.contains(r.classOf(declared).role)) {
                    daos.add(name);
                } else if (r.classOf(declared).qualifiedName.equals(classFqn)) {
                    internal.add(Labels.methodName(r, declared));
                } else if (role == ClassRole.UTIL) {
                    utils.add(Labels.methodName(r, declared));
                }
            }
            if (s.sql != null) {
                String id = s.sql.statementId != null ? s.sql.statementId : s.sql.id;
                Map<String, Object> q = new LinkedHashMap<>();
                q.put("id", id == null ? "?" : id);
                if (s.sql.xmlFile != null) {
                    q.put("xml", s.sql.xmlFile);
                }
                q.put("tables", List.copyOf(s.sql.tables));
                sql.putIfAbsent(String.valueOf(id), q);
            }
            if ("BEAN_NAME".equals(s.implementationBasis) && s.implementationReason != null) {
                beans.add(s.implementationReason);
                if (s.implementationMethodId == null) {
                    unresolved.add(s.expression + " — " + s.implementationReason);
                }
            }
            if ("REFLECTION".equals(s.resolutionBasis)) {
                reflection.add(s.expression + " → " + Labels.methodName(r, r.method(s.targetMethodId)));
            }
            if (s.resolution == ResolutionStatus.UNRESOLVED || s.resolution == ResolutionStatus.AMBIGUOUS) {
                unresolved.add(s.expression + " — " + s.resolution + (s.resolutionReason == null ? "" : " " + s.resolutionReason));
            }
        }
        m.put("services", List.copyOf(services));
        m.put("daos", List.copyOf(daos));
        m.put("sql", List.copyOf(sql.values()));
        m.put("uses-utils", List.copyOf(utils));
        m.put("internal-calls", List.copyOf(internal));
        m.put("beans", List.copyOf(beans));
        m.put("reflection", List.copyOf(reflection));
        m.put("unresolved", List.copyOf(unresolved));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Object o = YAML.readValue(file.toFile(), Object.class);
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 새 분석 결과 + 이전 파일: 메서드는 signature 로 맞춘다. 사람이 관리하는 칸(GENERATED 밖의 키, 기본 note)은 이전 값 유지,
     * 이전에 있었는데 지금 없는 메서드는 지우지 않고 removed: true 로 끝에 남긴다. void-methods = 반환 타입 void 인 현재 메서드.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(String fqn, List<Map<String, Object>> fresh, Map<String, Object> old) {
        Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
        Object oldMethods = old.get("methods");
        if (oldMethods instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> mm && mm.get("signature") != null) {
                    previous.put(String.valueOf(mm.get("signature")), (Map<String, Object>) mm);
                }
            }
        }
        List<Map<String, Object>> methods = new ArrayList<>();
        Set<String> now = new LinkedHashSet<>();
        for (Map<String, Object> m : fresh) {
            String sig = (String) m.get("signature");
            now.add(sig);
            Map<String, Object> out = new LinkedHashMap<>(m);
            Map<String, Object> prev = previous.get(sig);
            out.put("note", prev != null && prev.get("note") != null ? prev.get("note") : "");
            if (prev != null) {
                prev.forEach((k, v) -> {
                    if (!GENERATED.contains(k) && !out.containsKey(k)) {
                        out.put(k, v); // 사람이 추가한 칸
                    }
                });
            }
            methods.add(out);
        }
        for (Map.Entry<String, Map<String, Object>> e : previous.entrySet()) {
            if (!now.contains(e.getKey())) {
                Map<String, Object> gone = new LinkedHashMap<>(e.getValue());
                gone.put("removed", true); // 소스에서 없어진 메서드 — 지우지 않고 표시
                methods.add(gone);
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("class", fqn);
        doc.put("methods", methods);
        List<String> voids = new ArrayList<>();
        for (Map<String, Object> m : fresh) {
            if ("void".equals(m.get("returns"))) {
                voids.add((String) m.get("signature"));
            }
        }
        doc.put("void-methods", voids);
        return doc;
    }
}
