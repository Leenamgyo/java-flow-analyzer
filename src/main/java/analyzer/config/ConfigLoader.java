package analyzer.config;

import analyzer.model.ClassRole;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class ConfigLoader {
    private static final Set<String> DIRECTIONS = Set.of("TD", "TB", "BT", "LR", "RL");

    private ConfigLoader() {
    }

    /** 내장 기본 조건 (src/main/resources/analyzer-defaults.yml) */
    public static final String DEFAULTS_RESOURCE = "/analyzer-defaults.yml";

    private static ObjectMapper mapper() {
        return new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /** 내장 기본 조건 위에 프로젝트 설정 파일을 덮어쓴다 (맵은 키 단위 병합, 목록·값은 교체). 알 수 없는 키는 오류 */
    public static AnalyzerConfig load(Path file) throws IOException {
        ObjectMapper mapper = mapper();
        JsonNode merged = defaultsTree(mapper);
        JsonNode user;
        try (var in = Files.newInputStream(file)) {
            user = mapper.readTree(in);
        }
        if (user != null && !user.isMissingNode() && !user.isNull()) {
            if (!user.isObject()) {
                throw new IllegalArgumentException("설정 파일 최상위는 맵이어야 합니다: " + file);
            }
            merge((ObjectNode) merged, (ObjectNode) user);
        }
        return toConfig(mapper, merged);
    }

    /** 프로젝트 설정 파일 없이 내장 기본 조건만 */
    public static AnalyzerConfig defaults() throws IOException {
        ObjectMapper mapper = mapper();
        return toConfig(mapper, defaultsTree(mapper));
    }

    private static JsonNode defaultsTree(ObjectMapper mapper) throws IOException {
        try (var in = ConfigLoader.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("내장 기본 설정이 없습니다: " + DEFAULTS_RESOURCE);
            }
            return mapper.readTree(in);
        }
    }

    private static AnalyzerConfig toConfig(ObjectMapper mapper, JsonNode tree) throws IOException {
        AnalyzerConfig cfg = mapper.treeToValue(tree, AnalyzerConfig.class);
        return cfg == null ? new AnalyzerConfig() : cfg;
    }

    /** 맵은 재귀 병합, 그 밖(목록·스칼라·null)은 덮어쓴 값으로 교체 */
    static void merge(ObjectNode base, ObjectNode over) {
        over.fields().forEachRemaining(e -> {
            JsonNode b = base.get(e.getKey());
            if (b != null && b.isObject() && e.getValue().isObject()) {
                merge((ObjectNode) b, (ObjectNode) e.getValue());
            } else {
                base.set(e.getKey(), e.getValue());
            }
        });
    }

    public static void validate(AnalyzerConfig cfg) {
        if (cfg.target.effectiveSourceRoots().isEmpty()) {
            throw new IllegalArgumentException("target.source-root 또는 target.source-roots 가 필요합니다.");
        }
        List<Path> roots = cfg.target.effectiveSourceRoots().stream().map(x -> Path.of(x).toAbsolutePath().normalize()).toList();
        for (Path a : roots) {
            for (Path b : roots) {
                if (a != b && b.startsWith(a)) {
                    throw new IllegalArgumentException("source-roots 가 서로 겹칩니다(한 루트가 다른 루트 안): " + a + " ⊃ " + b);
                }
            }
        }
        if (cfg.target.className == null || cfg.target.className.isBlank()) {
            throw new IllegalArgumentException("target.class 가 필요합니다.");
        }
        if (cfg.analysis.maxDepth < 1) {
            throw new IllegalArgumentException("analysis.max-depth 는 1 이상이어야 합니다.");
        }
        if (!Set.of("elk", "dagre").contains(cfg.output.layout)) {
            throw new IllegalArgumentException("output.layout 은 elk 또는 dagre 여야 합니다.");
        }
        if (!DIRECTIONS.contains(cfg.output.direction)) {
            throw new IllegalArgumentException("output.direction 은 " + DIRECTIONS + " 중 하나여야 합니다.");
        }
        List<String> roles = new java.util.ArrayList<>(cfg.analysis.expandRoles);
        roles.addAll(cfg.phases.logicRoles);
        roles.addAll(cfg.phases.checkRoles);
        for (String role : roles) {
            if (ClassRole.fromLabel(role).isEmpty()) {
                throw new IllegalArgumentException("알 수 없는 역할(expand-roles / phases): " + role
                        + " (가능: " + java.util.Arrays.stream(ClassRole.values()).map(ClassRole::label).toList() + ")");
            }
        }
        for (var rule : cfg.requestParameterRules) {
            if (rule.methods == null || rule.methods.isEmpty()) {
                throw new IllegalArgumentException("request-parameter-rules 항목에는 methods 가 필요합니다 (class 는 생략 가능).");
            }
            for (var m : rule.methods) {
                if (m.name == null || m.name.isBlank()) {
                    throw new IllegalArgumentException("request-parameter-rules.methods 항목에는 name 이 필요합니다.");
                }
                if (m.nameArgIndex != null && m.parameter != null) {
                    throw new IllegalArgumentException("request-parameter-rules." + m.name + ": name-arg-index 와 parameter 는 함께 쓸 수 없습니다.");
                }
            }
        }
    }
}
