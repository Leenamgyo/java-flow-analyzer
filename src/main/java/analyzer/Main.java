package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.model.ClassInfo;
import analyzer.model.ModelValidator;
import analyzer.parser.AnalysisException;
import analyzer.serializer.OutputWriter;

import java.nio.file.Path;
import java.util.List;

/**
 * 사용법:
 * <pre>
 *   ./gradlew run --args="--config config/analyzer.yml"
 *   ./gradlew run --args="--config config/analyzer.yml --class ScpAppListAction --method execute"
 * </pre>
 * 옵션: --config, --source-root, --class, --method, --output
 */
public final class Main {

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (AnalysisException | IllegalArgumentException e) {
            System.err.println("[ERROR] " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("[ERROR] " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static int run(String[] args) throws Exception {
        Path configPath = Path.of("config/analyzer.yml");
        String sourceRoot = null, clazz = null, method = null, output = null;
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--config" -> { configPath = Path.of(require(v, "--config")); i++; }
                case "--source-root" -> { sourceRoot = require(v, "--source-root"); i++; }
                case "--class" -> { clazz = require(v, "--class"); i++; }
                case "--method" -> { method = require(v, "--method"); i++; }
                case "--output" -> { output = require(v, "--output"); i++; }
                default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i]);
            }
        }
        AnalyzerConfig cfg = ConfigLoader.load(configPath);
        if (sourceRoot != null) cfg.target.sourceRoot = sourceRoot;
        if (clazz != null) cfg.target.className = clazz;
        if (method != null) cfg.target.entryMethod = method;
        if (output != null) cfg.output.directory = output;
        ConfigLoader.validate(cfg);

        AnalysisResult result = analyze(cfg);
        ClassInfo target = result.clazz(result.targetClassId);
        Path outDir = outputDir(cfg, target.name);
        List<Path> files = new OutputWriter().write(result, cfg, outDir);

        System.out.println("분석 완료: " + target.qualifiedName + " → " + outDir);
        System.out.printf("  classes=%d methods=%d calls=%d blocks=%d parameters=%d objects=%d bindings=%d unresolved-calls=%d%n",
                result.classes().size(), result.methods().size(), result.callSites.size(), result.blocks.size(),
                result.parameters.size(), result.objects.size(), result.bindings.size(),
                result.callSites.stream().filter(s -> s.resolution != analyzer.model.ResolutionStatus.RESOLVED).count());
        files.forEach(f -> System.out.println("  - " + f.getFileName()));
        return 0;
    }

    /** 분석 + 모델 무결성 검증 */
    public static AnalysisResult analyze(AnalyzerConfig cfg) {
        AnalysisResult result = new FlowAnalyzer(cfg).analyze();
        List<String> errors = ModelValidator.validate(result);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("분석 모델 무결성 오류:\n" + String.join("\n", errors));
        }
        return result;
    }

    /** 원본 소스 디렉터리 안에는 절대 쓰지 않는다. */
    public static Path outputDir(AnalyzerConfig cfg, String className) {
        Path src = Path.of(cfg.target.sourceRoot).toAbsolutePath().normalize();
        Path out = Path.of(cfg.output.directory).toAbsolutePath().normalize().resolve(className);
        if (out.startsWith(src)) {
            throw new IllegalArgumentException("output 디렉터리가 source-root 내부입니다. 원본 프로젝트에는 쓰지 않습니다: " + out);
        }
        return out;
    }

    private static String require(String v, String opt) {
        if (v == null) {
            throw new IllegalArgumentException(opt + " 값이 필요합니다.");
        }
        return v;
    }
}
