package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.HtmlFlowchart;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.phase.PhaseAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내장 기본 조건(analyzer-defaults.yml — expand-roles·expand-classes·phases 등, 실제 실행과 같음)으로 모든 fixture Action 의
 * public 메서드를 설정 3종(기본 / 외부 라이브러리 포함 / max-depth 4)으로 분석해 일반·구간·HTML·단계 보기가 검증
 * (노드 누락 등)을 통과하는지. 다른 테스트는 최소 설정(Fixtures.config)이라 기본 조건에서만 나는 오류를 놓쳤다.
 */
class DefaultConfigSweepTest {
    @Test
    void everyEntryRendersWithDefaultConfig() throws Exception {
        Path base = Path.of("src/test/resources/sample-project/src/main/java/com/example");
        Pattern p = Pattern.compile("^    public [\\w<>\\[\\], ]+ (\\w+)\\(", Pattern.MULTILINE);
        StringBuilder out = new StringBuilder();
        int n = 0;
        try (var files = Files.walk(base)) {
            for (Path f : files.filter(x -> x.toString().contains("/action/") && x.toString().endsWith(".java")).toList()) {
                String cls = f.getFileName().toString().replace(".java", "");
                Matcher m = p.matcher(Files.readString(f));
                Set<String> meths = new LinkedHashSet<>();
                while (m.find()) meths.add(m.group(1));
                for (String meth : meths) {
                    for (int v = 0; v < 3; v++) {
                        AnalyzerConfig cfg = ConfigLoader.defaults();
                        cfg.target.sourceRoot = Fixtures.SOURCE_ROOT;
                        cfg.target.className = cls;
                        cfg.target.entryMethod = meth;
                        if (v == 1) cfg.analysis.includeExternalLibraryCalls = true;
                        if (v == 2) cfg.analysis.maxDepth = 4;
                        AnalysisResult r;
                        try { r = Main.analyze(cfg); } catch (Throwable e) { continue; }
                        n++;
                        String tag = cls + "." + meth + " [" + v + "] ";
                        try {
                            FlowchartGenerator gen = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles));
                            gen.render(r, "TD");
                            new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles)).renderSectioned(r, "TD", "dagre", 60);
                            FlowchartGenerator hg = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles));
                            HtmlFlowchart.render(r, hg.checked(r), hg.classDefinitions(), "TD", "t");
                        } catch (Throwable e) { out.append("FLOW ").append(tag).append(e.getMessage()).append('\n'); }
                        try {
                            new FlowchartGenerator(PhaseAnalyzer.analyze(r, cfg.phases)).render(r, "TD");
                        } catch (Throwable e) { out.append("PHASE ").append(tag).append(e.getMessage()).append('\n'); }
                    }
                }
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(n > 100, "분석한 진입 메서드 수: " + n);
        org.junit.jupiter.api.Assertions.assertEquals("", out.toString());
    }
}
