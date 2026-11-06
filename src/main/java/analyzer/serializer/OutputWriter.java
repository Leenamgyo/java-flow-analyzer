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
        String tree = new CallTreeWriter(cfg.output.treeIncludeControlBlocks).render(r);
        if (cfg.output.tree) {
            Files.writeString(dir.resolve("call-tree.txt"), tree);
            written.add(dir.resolve("call-tree.txt"));
        }
        String mermaid = null;
        String mermaidStatus = "출력 비활성화";
        if (cfg.output.mermaid) {
            Path mmd = dir.resolve("flowchart.mmd");
            mermaid = new FlowchartGenerator().render(r, cfg.output.direction);
            Files.writeString(mmd, mermaid);
            written.add(mmd);
            mermaidStatus = MermaidCli.validate(mmd);
        }
        Files.writeString(dir.resolve("mapping.md"), new MappingReportWriter().render(r));
        written.add(dir.resolve("mapping.md"));
        Files.writeString(dir.resolve("analysis.md"), new AnalysisReportWriter().render(r, tree, mermaid, mermaidStatus));
        written.add(dir.resolve("analysis.md"));
        return written;
    }
}
