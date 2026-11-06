package analyzer.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (cfg.target.sourceRoot == null || cfg.target.sourceRoot.isBlank()) {
            throw new IllegalArgumentException("target.source-root 가 필요합니다.");
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
        for (var rule : cfg.requestParameterRules) {
            if (rule.className == null || rule.methods == null || rule.methods.isEmpty()) {
                throw new IllegalArgumentException("request-parameter-rules 항목에는 class 와 methods 가 필요합니다.");
            }
        }
    }
}
