package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.model.ClassInfo;
import analyzer.model.ModelValidator;
import analyzer.parser.AnalysisException;
import analyzer.serializer.OutputWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 사용법:
 * <pre>
 *   ./gradlew run                                                           # 기본 조건 + config/analyzer.yml
 *   ./gradlew run --args="--config my-project.yml --class XxxAction"        # 프로젝트 설정 + Action 지정
 *   ./gradlew run --args="--source-root /abs/src/main/java --class XAction" # 기본 조건만 + 경로
 *   ./gradlew run --args="--config my-project.yml --class ContentUtil --all-methods"   # Util 기능 목록 → catalog/ContentUtil.yml
 * </pre>
 * 옵션: --config, --source-root(반복 가능: 멀티 모듈), --class, --method, --output, --jsp, --all-methods, --catalog-reset, --catalog-prune,
 *       --usage(사용처 역색인 → catalog/usage.md), --who-uses <클래스.메서드>
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
        Path configPath = null;
        List<String> sourceRoots = new java.util.ArrayList<>();
        String clazz = null, method = null, output = null;
        boolean allMethods = false;
        boolean usage = false;
        String whoUses = null;
        String catalogDir = null;
        analyzer.catalog.UtilCatalog.Mode catalogMode = analyzer.catalog.UtilCatalog.Mode.UPDATE;
        List<String> jsp = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--config" -> { configPath = Path.of(require(v, "--config")); i++; }
                case "--source-root" -> { sourceRoots.add(require(v, "--source-root")); i++; }
                case "--class" -> { clazz = require(v, "--class"); i++; }
                case "--method" -> { method = require(v, "--method"); i++; }
                case "--output" -> { output = require(v, "--output"); i++; }
                case "--jsp" -> { jsp.add(require(v, "--jsp")); i++; }
                case "--all-methods" -> allMethods = true;
                case "--catalog-dir" -> { catalogDir = require(v, "--catalog-dir"); i++; }   // 카탈로그 폴더 (기본 catalog)
                case "--usage" -> usage = true;                                      // run 카탈로그 → catalog/usage.md
                case "--who-uses" -> { whoUses = require(v, "--who-uses"); i++; }    // 이 메서드를 쓰는 Action (콘솔)
                case "--catalog-reset" -> catalogMode = analyzer.catalog.UtilCatalog.Mode.RESET;   // 기능 목록을 지금 소스로 새로 (백업 후)
                case "--catalog-prune" -> catalogMode = analyzer.catalog.UtilCatalog.Mode.PRUNE;   // 없어진(removed) 메서드만 정리 (백업 후)
                default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i]);
            }
        }
        // 설정: 내장 기본 조건 < 프로젝트 설정 파일 < CLI.
        // --config 가 없으면: --source-root 를 줬으면 기본 조건만, 아니면 ./config/analyzer.yml (있을 때)
        if (configPath == null && sourceRoots.isEmpty() && Files.isRegularFile(Path.of("config/analyzer.yml"))) {
            configPath = Path.of("config/analyzer.yml");
        }
        AnalyzerConfig cfg = configPath == null ? ConfigLoader.defaults() : ConfigLoader.load(configPath);
        if (!sourceRoots.isEmpty()) {   // CLI 지정 시 설정 파일의 루트를 대체
            cfg.target.sourceRoot = null;
            cfg.target.sourceRoots = sourceRoots;
        }
        if (clazz != null) cfg.target.className = clazz;
        if (method != null) cfg.target.entryMethod = method;
        if (output != null) cfg.output.directory = output;
        if (!jsp.isEmpty()) cfg.response.jsp = jsp;   // Action 마다 JSP 가 다르므로 실행 단위로 지정
        if (catalogDir != null) cfg.output.catalogDir = catalogDir;
        if (usage || whoUses != null) { // 사용처 역색인: run 카탈로그만 읽음 (분석 없음)
            if (whoUses != null) {
                var found = analyzer.catalog.RunCatalog.whoUses(cfg, whoUses);
                System.out.println("'" + whoUses + "' 사용처 (" + analyzer.catalog.RunCatalog.dir(cfg) + " 기준)");
                if (found.isEmpty()) {
                    System.out.println("  없음 — 이름을 확인하거나, 그 메서드를 쓰는 Action 을 먼저 run 으로 분석하세요");
                }
                found.forEach((m, where) -> System.out.println("  " + m + "  ← " + where.size() + "곳: " + String.join(", ", where)));
            }
            if (usage) {
                System.out.println("사용처 역색인: " + analyzer.catalog.RunCatalog.writeUsage(cfg));
            }
            return 0;
        }
        if (allMethods) cfg.target.allMethods = true;
        if (catalogMode != analyzer.catalog.UtilCatalog.Mode.UPDATE && !cfg.target.allMethods) {
            throw new IllegalArgumentException("--catalog-reset·--catalog-prune 은 기능 목록 모드(--all-methods 또는 target.all-methods)에서만 씁니다.");
        }
        ConfigLoader.validate(cfg);

        if (cfg.target.allMethods) { // Util 기능 목록: 메서드 전부 → catalog/<클래스>.yml (계속 관리되는 파일, 갱신)
            Path file = analyzer.catalog.UtilCatalog.run(cfg, catalogMode);
            System.out.println("기능 목록 갱신: " + cfg.target.className + " → " + file);
            return 0;
        }

        AnalysisResult result = analyze(cfg);
        ClassInfo target = result.clazz(result.targetClassId);
        Path outDir = outputDir(cfg, target.name);
        analyzer.response.ResponseModel response = cfg.response.jsp.isEmpty() ? null
                : analyzer.response.ResponseAnalyzer.analyze(cfg, result);
        List<Path> files = new OutputWriter().write(result, cfg, outDir, response);
        if (cfg.output.runCatalog) { // run 카탈로그: 이 진입 메서드가 쓰는 메서드 누적 (사용처 역색인용)
            System.out.println("run 카탈로그: " + analyzer.catalog.RunCatalog.write(result, cfg));
        }

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
        Path out = Path.of(cfg.output.directory).toAbsolutePath().normalize().resolve(className);
        for (String root : cfg.target.effectiveSourceRoots()) {
            Path src = Path.of(root).toAbsolutePath().normalize();
            if (out.startsWith(src)) {
                throw new IllegalArgumentException("output 디렉터리가 source-root 내부입니다. 원본 프로젝트에는 쓰지 않습니다: " + out);
            }
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
