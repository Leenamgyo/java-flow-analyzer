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
        if (cfg.output.mermaid) {
            Path mmd = dir.resolve("flowchart.mmd");
            mermaid = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                    .maxLineLength(cfg.output.maxLineLength).exclude(cfg.output.flowchartExclude).render(r, cfg.output.direction, cfg.output.layout);
            Files.writeString(mmd, mermaid);
            written.add(mmd);
            mermaidStatus = MermaidCli.validate(mmd);
            if (cfg.output.splitFlowchart) {
                // 큰 흐름도: 메서드별 파일 + 전체 구조 + 합본 flowchart.md
                FlowchartGenerator gen = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles))
                        .maxLineLength(cfg.output.maxLineLength).exclude(cfg.output.flowchartExclude);
                List<FlowchartGenerator.Part> parts = gen.renderSplit(r, cfg.output.direction, cfg.output.layout);
                Path sub = dir.resolve("flowchart");
                if (Files.isDirectory(sub)) { // 이전 실행의 분할 파일 정리 (.mmd 만)
                    try (var old = Files.list(sub)) {
                        for (Path f : old.filter(f -> f.toString().endsWith(".mmd")).toList()) {
                            Files.delete(f);
                        }
                    }
                }
                Files.createDirectories(sub);
                for (FlowchartGenerator.Part p : parts) {
                    Files.writeString(sub.resolve(p.file()), p.mermaid());
                }
                written.add(sub);
                var entry = r.method(r.entryMethodId);
                Path md = dir.resolve("flowchart.md");
                Files.writeString(md, FlowchartGenerator.combinedMarkdown(r.classOf(entry).name + "." + entry.name + "()", parts));
                written.add(md);
            }
        }
        if (cfg.phases.enabled) {
            analyzer.phase.PhaseModel pm = analyzer.phase.PhaseAnalyzer.analyze(r, cfg.phases);
            if (cfg.output.mermaid) {
                Path pmmd = dir.resolve("phase-flowchart.mmd");
                try {
                    Files.writeString(pmmd, new FlowchartGenerator(pm).maxLineLength(cfg.output.maxLineLength).exclude(cfg.output.flowchartExclude)
                            .render(r, cfg.output.direction, cfg.output.layout));
                    written.add(pmmd);
                    MermaidCli.validate(pmmd);
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
        Files.writeString(dir.resolve("mapping.md"), new MappingReportWriter().render(r));
        written.add(dir.resolve("mapping.md"));
        Files.writeString(dir.resolve("analysis.md"), new AnalysisReportWriter().render(r, mermaidStatus));
        written.add(dir.resolve("analysis.md"));
        return written;
    }
}
