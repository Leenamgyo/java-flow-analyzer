package analyzer.config;

import analyzer.model.ClassRole;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

    public static AnalyzerConfig load(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        try (var in = Files.newInputStream(file)) {
            AnalyzerConfig cfg = mapper.readValue(in, AnalyzerConfig.class);
            return cfg == null ? new AnalyzerConfig() : cfg;
        }
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
        if (!DIRECTIONS.contains(cfg.output.direction)) {
            throw new IllegalArgumentException("output.direction 은 " + DIRECTIONS + " 중 하나여야 합니다.");
        }
        for (String role : cfg.analysis.expandRoles) {
            if (ClassRole.fromLabel(role).isEmpty()) {
                throw new IllegalArgumentException("analysis.expand-roles 의 알 수 없는 역할: " + role
                        + " (가능: " + java.util.Arrays.stream(ClassRole.values()).map(ClassRole::label).toList() + ")");
            }
        }
        for (var rule : cfg.requestParameterRules) {
            if (rule.className == null || rule.methods == null || rule.methods.isEmpty()) {
                throw new IllegalArgumentException("request-parameter-rules 항목에는 class 와 methods 가 필요합니다.");
            }
        }
    }
}
