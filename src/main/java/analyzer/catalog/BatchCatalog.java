package analyzer.catalog;

import analyzer.Main;
import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.parser.ProjectSources;
import analyzer.serializer.OutputWriter;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 일괄 카탈로그 (사용자: 일괄로 — 모든 메서드에 역연산). 소스의 Action/Controller 전부를 진입 메서드마다 분석해 run 카탈로그를,
 * Util 전부를 기능 목록으로 만든다. 분석 규칙은 하나씩 돌릴 때와 같다(Main.analyze). 하나가 실패해도 계속하고 요약을 낸다.
 * 기본은 카탈로그만 쓰고 output/ 상세 파일은 만들지 않는다(빠르게) — withOutputs 면 함께.
 */
public final class BatchCatalog {

    public record Summary(int ok, List<String> failed, List<String> skipped) {
    }

    private BatchCatalog() {
    }

    public static Summary actions(AnalyzerConfig cfg, boolean withOutputs) throws IOException {
        List<ClassOrInterfaceDeclaration> classes = SourceScan.actions(sources(cfg));
        List<Target> targets = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ClassOrInterfaceDeclaration ci : classes) {
            List<MethodDeclaration> entries = SourceScan.entryMethods(ci);
            if (entries.isEmpty()) {
                skipped.add(SourceScan.fqn(ci) + " — 진입 메서드 없음 (매핑 어노테이션·public execute 없음, --class --method 로 직접)");
            }
            entries.forEach(m -> targets.add(new Target(ci, m, entries.size() > 1)));
        }
        System.out.println("Action/Controller " + classes.size() + "개 · 진입 메서드 " + targets.size() + "개 → run 카탈로그");
        Saved saved = Saved.of(cfg);
        int ok = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                String label = t.ci.getNameAsString() + "." + t.m.getNameAsString();
                cfg.target.className = SourceScan.fqn(t.ci);
                cfg.target.entryMethod = t.m.getNameAsString();
                cfg.target.entryMethodParameterTypes = t.m.getParameters().stream()
                        .map(p -> p.getType().asString() + (p.isVarArgs() ? "..." : "")).toList();
                try {
                    AnalysisResult r = Main.analyze(cfg);
                    RunCatalog.write(r, cfg);
                    if (withOutputs) {
                        Path dir = Main.outputDir(cfg, t.ci.getNameAsString());
                        new OutputWriter().write(r, cfg, t.many ? dir.resolve(t.m.getNameAsString()) : dir);
                    }
                    ok++;
                    System.out.println("  [" + (i + 1) + "/" + targets.size() + "] " + label + " — 쓰는 메서드 " + CatalogKeys.uses(r).size() + "개");
                } catch (RuntimeException e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
                    failed.add(label + " — " + msg);
                    System.err.println("  [" + (i + 1) + "/" + targets.size() + "] " + label + " 실패 — " + msg);
                }
            }
        } finally {
            saved.restore(cfg);
        }
        return new Summary(ok, failed, skipped);
    }

    public static Summary utils(AnalyzerConfig cfg, boolean withOutputs) throws IOException {
        List<ClassOrInterfaceDeclaration> classes = SourceScan.utils(sources(cfg));
        System.out.println("Util " + classes.size() + "개 → Util 기능 목록");
        Saved saved = Saved.of(cfg);
        boolean outputs = cfg.output.catalogOutputs;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        try {
            cfg.output.catalogOutputs = withOutputs;
            for (int i = 0; i < classes.size(); i++) {
                ClassOrInterfaceDeclaration ci = classes.get(i);
                cfg.target.className = SourceScan.fqn(ci);
                try {
                    Path f = UtilCatalog.run(cfg);
                    ok++;
                    System.out.println("  [" + (i + 1) + "/" + classes.size() + "] " + ci.getNameAsString() + " → " + f.getFileName());
                } catch (RuntimeException e) {
                    failed.add(ci.getNameAsString() + " — " + e.getMessage());
                    System.err.println("  [" + (i + 1) + "/" + classes.size() + "] " + ci.getNameAsString() + " 실패 — " + e.getMessage());
                }
            }
        } finally {
            cfg.output.catalogOutputs = outputs;
            saved.restore(cfg);
        }
        return new Summary(ok, failed, List.of());
    }

    private static ProjectSources sources(AnalyzerConfig cfg) {
        return new ProjectSources(cfg.target.effectiveSourceRoots().stream().map(Path::of).toList(),
                cfg.analysis.classpathJars.stream().map(Path::of).toList());
    }

    private record Target(ClassOrInterfaceDeclaration ci, MethodDeclaration m, boolean many) {
    }

    private record Saved(String className, String method, List<String> types) {
        static Saved of(AnalyzerConfig cfg) {
            return new Saved(cfg.target.className, cfg.target.entryMethod, cfg.target.entryMethodParameterTypes);
        }

        void restore(AnalyzerConfig cfg) {
            cfg.target.className = className;
            cfg.target.entryMethod = method;
            cfg.target.entryMethodParameterTypes = types;
        }
    }
}
