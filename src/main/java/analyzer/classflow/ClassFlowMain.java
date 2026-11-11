package analyzer.classflow;

import analyzer.FlowAnalyzer;
import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.HtmlFlowchart;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.ModelValidator;
import analyzer.parser.AnalysisException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 전체 클래스 흐름도 (Action 분석과 따로 — 소스·실행 명령·결과 폴더 분리, 사용자 요구). 클래스(ContentUtil 등)의 메서드 전부를
 * 진입으로 한 번에 분석(핵심 분석 FlowAnalyzer 공유, 규칙 같음)하고 flowchart.html 하나만 만든다 (VO·spec 등 없음).
 * <pre>
 *   ./gradlew runClass --args="--config config/my-project.yml --class ContentUtil"
 *   ./gradlew runClass --args="--config config/my-project.yml --class ContentUtil --apply"   # catalog/ContentUtil.yml 의 hidden 적용
 * </pre>
 * 옵션: --config, --source-root(반복), --class, --output, --apply. 결과: &lt;output&gt;/class/&lt;클래스&gt;/flowchart.html
 */
public final class ClassFlowMain {

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
        List<String> roots = new ArrayList<>();
        String clazz = null, output = null;
        boolean apply = false;
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--config" -> { configPath = Path.of(require(v, "--config")); i++; }
                case "--source-root" -> { roots.add(require(v, "--source-root")); i++; }
                case "--class" -> { clazz = require(v, "--class"); i++; }
                case "--output" -> { output = require(v, "--output"); i++; }
                case "--apply" -> apply = true; // catalog/<클래스>.yml 의 hidden 을 흐름도에 적용
                default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i] + " (runClass: --config --source-root --class --output --apply)");
            }
        }
        if (configPath == null && roots.isEmpty() && Files.isRegularFile(Path.of("config/analyzer.yml"))) {
            configPath = Path.of("config/analyzer.yml");
        }
        AnalyzerConfig cfg = configPath == null ? ConfigLoader.defaults() : ConfigLoader.load(configPath);
        if (!roots.isEmpty()) {
            cfg.target.sourceRoot = null;
            cfg.target.sourceRoots = roots;
        }
        if (clazz != null) cfg.target.className = clazz;
        if (output != null) cfg.output.directory = output;
        ConfigLoader.validate(cfg);

        AnalysisResult r = analyze(cfg);
        String name = r.clazz(r.targetClassId).name;
        List<String> hidden = null;
        if (apply) {
            Path catalog = analyzer.catalog.UtilCatalog.catalogFile(cfg, name);
            List<String> patterns = CatalogApply.hiddenPatterns(catalog);
            hidden = CatalogApply.matchMethods(r, patterns);
            System.out.println("카탈로그 적용: " + catalog + " — hidden " + patterns.size() + "개 → 숨긴 메서드 상자 " + hidden.size() + "개"
                    + (Files.isRegularFile(catalog) ? "" : " (카탈로그 파일 없음)"));
            CatalogApply.unmatched(r, patterns).forEach(p -> System.err.println("[WARN] hidden '" + p + "' 에 맞는 메서드 없음"));
        }
        Path file = write(r, cfg, outputDir(cfg, name), hidden);
        System.out.println("전체 클래스 흐름도: " + r.clazz(r.targetClassId).qualifiedName + " (메서드 " + r.entryMethodIds.size() + "개) → " + file);
        return 0;
    }

    /** 분석 (메서드 전부를 진입으로) + 모델 무결성 검증 */
    public static AnalysisResult analyze(AnalyzerConfig cfg) {
        AnalysisResult r = new FlowAnalyzer(cfg).analyzeClass();
        List<String> errors = ModelValidator.validate(r);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("분석 모델 무결성 오류:\n" + String.join("\n", errors));
        }
        return r;
    }

    /** &lt;output&gt;/class/&lt;클래스&gt; — Action 분석 결과(&lt;output&gt;/&lt;클래스&gt;)와 섞이지 않게. 원본 소스 안에는 쓰지 않는다 */
    public static Path outputDir(AnalyzerConfig cfg, String className) {
        return analyzer.Main.outputDir(cfg, "class").resolve(className);
    }

    /** flowchart.html 하나 (Mermaid 흐름도와 같은 그래프·검증) */
    public static Path write(AnalysisResult r, AnalyzerConfig cfg, Path dir) throws java.io.IOException {
        return write(r, cfg, dir, null);
    }

    /** hidden: 처음부터 숨길 메서드 ID (카탈로그 적용), null 이면 없음 */
    public static Path write(AnalysisResult r, AnalyzerConfig cfg, Path dir, List<String> hidden) throws java.io.IOException {
        FlowchartGenerator gen = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                .maxLineLength(cfg.output.maxLineLength).maxNodeLines(cfg.output.maxNodeLines)
                .exclude(cfg.output.flowchartExclude).theme(cfg.output.colorTheme);
        MermaidGraph g = gen.checked(r);
        Files.createDirectories(dir);
        Path file = dir.resolve("flowchart.html");
        Files.writeString(file, HtmlFlowchart.render(r, g, gen.classDefinitions(), cfg.output.direction,
                r.clazz(r.targetClassId).name + " — 전체 클래스 흐름도", cfg.output.flowchartHtmlHideNodes,
                FlowchartGenerator.roles(cfg.phases.logicRoles), hidden));
        return file;
    }

    private static String require(String v, String opt) {
        if (v == null) {
            throw new IllegalArgumentException(opt + " 값이 필요합니다.");
        }
        return v;
    }
}
