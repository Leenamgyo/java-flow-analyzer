package analyzer.serializer;

import analyzer.config.AnalyzerConfig;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidCli;
import analyzer.model.AnalysisResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 동일 모델로 모든 출력 파일을 생성한다.
 * analysis.yaml(원본 데이터, 스키마 검증) · flowchart.mmd · mapping.md · analysis.md · call-tree.txt
 * (+ phases.enabled: phase-flowchart.mmd · phase-summary.md — 바인딩 › 체크 › 로직 › 후처리 보기)
 */
public final class OutputWriter {

    public List<Path> write(AnalysisResult r, AnalyzerConfig cfg, Path dir) throws IOException {
        return write(r, cfg, dir, null);
    }

    /** response: Response(JSP) 분석 결과 (response.jsp 를 지정했을 때만, 없으면 null) */
    public List<Path> write(AnalysisResult r, AnalyzerConfig cfg, Path dir, analyzer.response.ResponseModel response)
            throws IOException {
        Files.createDirectories(dir);
        List<Path> written = new ArrayList<>();
        if (cfg.output.yaml) {
            Path yaml = dir.resolve("analysis.yaml");
            new AnalysisYamlWriter().write(r, yaml);
            written.add(yaml);
        }
        String tree = new CallTreeWriter(cfg.output.treeIncludeControlBlocks).exclude(cfg.output.callTreeExclude).render(r);
        if (cfg.output.tree) {
            Files.writeString(dir.resolve("call-tree.txt"), tree);
            written.add(dir.resolve("call-tree.txt"));
        }
        String mermaid = null;
        String mermaidStatus = "출력 비활성화";
        List<Path> charts = new ArrayList<>(); // SVG 로 그릴 .mmd 들
        if (cfg.output.mermaid) {
            Path mmd = dir.resolve("flowchart.mmd");
            mermaid = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                    .maxLineLength(cfg.output.maxLineLength).maxNodeLines(cfg.output.maxNodeLines).exclude(cfg.output.flowchartExclude).theme(cfg.output.colorTheme)
                    .render(r, cfg.output.direction, cfg.output.layout);
            Files.writeString(mmd, mermaid);
            written.add(mmd);
            charts.add(mmd);
            if (!cfg.output.flowchartSvg) { // SVG 를 만들면 그 렌더링이 검증을 겸한다
                mermaidStatus = MermaidCli.validate(mmd);
            }
            // 예전 메서드별 분할 출력(flowchart/, flowchart.md)은 구간 분할·골격으로 대체 — 이전 실행이 남긴 파일 정리
            Path legacy = dir.resolve("flowchart");
            if (Files.isDirectory(legacy)) {
                try (var old = Files.list(legacy)) {
                    for (Path f : old.filter(f -> f.toString().endsWith(".mmd")).toList()) {
                        Files.delete(f);
                    }
                }
                try (var rest = Files.list(legacy)) {
                    if (rest.findAny().isEmpty()) {
                        Files.delete(legacy);
                    }
                }
            }
            Files.deleteIfExists(dir.resolve("flowchart.md"));
            // 메서드별 보기: 구간 분할(flowchart-sections, 기본 켜짐) · 골격(flowchart-skeleton, 기본 꺼짐) — 각각 켜고 끔
            if (cfg.output.flowchartSections || cfg.output.flowchartSkeleton) {
                FlowchartGenerator.Sectioned sec = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                        .maxLineLength(cfg.output.maxLineLength).maxNodeLines(cfg.output.maxNodeLines).exclude(cfg.output.flowchartExclude).theme(cfg.output.colorTheme)
                        .skeleton(cfg.output.flowchartSkeleton)
                        .renderSectioned(r, cfg.output.direction, cfg.output.layout, cfg.output.sectionSize);
                var entry = r.method(r.entryMethodId);
                String title = r.classOf(entry).name + "." + entry.name + "()";
                if (cfg.output.flowchartSections) {
                    writeParts(dir, "flowchart-sections", sec.sections(), charts, written);
                    Files.writeString(dir.resolve("flowchart-sections.md"), FlowchartGenerator.combinedMarkdown(title + " — 구간 분할", "flowchart-sections", sec.sections()));
                    written.add(dir.resolve("flowchart-sections.md"));
                }
                if (cfg.output.flowchartSkeleton) {
                    writeParts(dir, "flowchart-skeleton", sec.skeletons(), charts, written);
                    Files.writeString(dir.resolve("flowchart-skeleton.md"), FlowchartGenerator.combinedMarkdown(title + " — 골격", "flowchart-skeleton", sec.skeletons()));
                    written.add(dir.resolve("flowchart-skeleton.md"));
                }
            }
            // 끈 보기는 이전 실행 결과도 지운다 (용량)
            if (!cfg.output.flowchartSections) {
                removeParts(dir, "flowchart-sections");
            }
            if (!cfg.output.flowchartSkeleton) {
                removeParts(dir, "flowchart-skeleton");
            }
        }
        if (cfg.phases.enabled) {
            analyzer.phase.PhaseModel pm = analyzer.phase.PhaseAnalyzer.analyze(r, cfg.phases);
            if (cfg.output.mermaid) {
                Path pmmd = dir.resolve("phase-flowchart.mmd");
                try {
                    Files.writeString(pmmd, new FlowchartGenerator(pm).maxLineLength(cfg.output.maxLineLength).maxNodeLines(cfg.output.maxNodeLines).exclude(cfg.output.flowchartExclude)
                            .theme(cfg.output.colorTheme).render(r, cfg.output.direction, cfg.output.layout));
                    written.add(pmmd);
                    charts.add(pmmd);
                } catch (IllegalStateException e) {
                    // 단계 보기는 파생 보기 — 검증에 실패해도 나머지 출력(analysis.yaml·flowchart·mapping…)은 만든다
                    Files.deleteIfExists(pmmd);
                    System.err.println("[WARN] phase-flowchart.mmd 생략 — " + e.getMessage());
                }
            }
            Path ps = dir.resolve("phase-summary.md");
            Files.writeString(ps, new analyzer.phase.PhaseReportWriter().render(r, pm));
            written.add(ps);
        }
        if (cfg.output.mermaid && cfg.output.flowchartSvg) {
            mermaidStatus = svgs(cfg, dir, charts, written);
        }
        Files.writeString(dir.resolve("mapping.md"), new MappingReportWriter().render(r));
        written.add(dir.resolve("mapping.md"));
        Files.writeString(dir.resolve("analysis.md"), new AnalysisReportWriter().render(r, mermaidStatus));
        written.add(dir.resolve("analysis.md"));
        Files.writeString(dir.resolve("spec.md"), new SpecWriter().render(r, response)); // 명세 (1. Request Param 정의, JSP 지정 시 2. Response 정의)
        written.add(dir.resolve("spec.md"));
        Files.deleteIfExists(dir.resolve("response.md")); // 이전 실행의 결과 (이번에 JSP 를 지정하지 않았으면 남기지 않음)
        if (response != null) {
            Files.writeString(dir.resolve("response.md"), new analyzer.response.ResponseReportWriter().render(r, response));
            written.add(dir.resolve("response.md"));
        }
        return written;
    }

    /** 파트들을 dir/sub/ 에 (이전 실행의 .mmd 는 지운다) */
    /** 끈 보기의 이전 결과: 폴더의 .mmd, 빈 폴더, 합본 .md */
    private static void removeParts(Path dir, String sub) throws IOException {
        Path d = dir.resolve(sub);
        if (Files.isDirectory(d)) {
            try (var old = Files.list(d)) {
                for (Path f : old.filter(f -> f.toString().endsWith(".mmd")).toList()) {
                    Files.delete(f);
                }
            }
            try (var rest = Files.list(d)) {
                if (rest.findAny().isEmpty()) {
                    Files.delete(d);
                }
            }
        }
        Files.deleteIfExists(dir.resolve(sub + ".md"));
    }

    private static void writeParts(Path dir, String sub, List<FlowchartGenerator.Part> parts, List<Path> charts, List<Path> written)
            throws IOException {
        Path d = dir.resolve(sub);
        if (Files.isDirectory(d)) {
            try (var old = Files.list(d)) {
                for (Path f : old.filter(f -> f.toString().endsWith(".mmd")).toList()) {
                    Files.delete(f);
                }
            }
        }
        Files.createDirectories(d);
        for (FlowchartGenerator.Part p : parts) {
            Files.writeString(d.resolve(p.file()), p.mermaid());
            charts.add(d.resolve(p.file()));
        }
        written.add(d);
    }

    /**
     * 흐름도 .mmd 를 flowchart-svg/ 에 같은 이름의 .svg 로 (Mermaid CLI mmdc 필요, 없으면 생략하고 경고).
     * @return flowchart.mmd 렌더링 결과 (분석서 부록의 Mermaid 렌더링 검증)
     */
    private static String svgs(AnalyzerConfig cfg, Path dir, List<Path> charts, List<Path> written) throws IOException {
        Path out = dir.resolve("flowchart-svg");
        if (Files.isDirectory(out)) { // 이전 실행의 SVG 정리 (.svg 만, sections/·skeleton/ 포함)
            try (var old = Files.walk(out)) {
                for (Path f : old.filter(f -> f.toString().endsWith(".svg")).toList()) {
                    Files.delete(f);
                }
            }
        }
        var mmdc = MermaidCli.find(cfg.output.mermaidCli);
        if (mmdc.isEmpty()) {
            System.err.println("[WARN] flowchart-svg 생략 — Mermaid CLI(mmdc) 없음. 설치: npm install -g @mermaid-js/mermaid-cli"
                    + " (또는 output.mermaid-cli 에 mmdc 경로)");
            return "SKIPPED: Mermaid CLI(mmdc) 미설치 — flowchart-svg 생략, 렌더링 검증 생략 (문법 구조 검증만 수행)";
        }
        Files.createDirectories(out);
        String background = "dark".equals(cfg.output.colorTheme) ? "#0f172a" : "white";
        String status = "SKIPPED: flowchart.mmd 없음";
        int ok = 0;
        for (Path mmd : charts) {
            // 구간·골격은 메서드 파일과 이름이 겹칠 수 있어 하위 폴더로
            String parent = mmd.getParent().getFileName().toString();
            Path target = switch (parent) {
                case "flowchart-sections" -> out.resolve("sections");
                case "flowchart-skeleton" -> out.resolve("skeleton");
                default -> out;
            };
            Files.createDirectories(target);
            Path svg = target.resolve(mmd.getFileName().toString().replaceFirst("\\.mmd$", ".svg"));
            String fail = MermaidCli.svg(mmdc.get(), mmd, svg, background);
            if (fail == null) {
                ok++;
            } else {
                System.err.println("[WARN] " + dir.relativize(mmd) + " → SVG 실패: " + fail);
            }
            if (mmd.equals(dir.resolve("flowchart.mmd"))) {
                status = fail == null ? "OK: mmdc 렌더링 성공 (flowchart-svg/)" : "FAILED: " + fail;
            }
        }
        System.err.println("[INFO] flowchart-svg: " + ok + "/" + charts.size() + " 개 SVG");
        written.add(out);
        return status;
    }
}
