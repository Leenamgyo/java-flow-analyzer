package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.serializer.OutputWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 회귀 비교 도구 (환경 변수 GOLDEN_DIR 가 있을 때만 동작): 모든 fixture Action public 메서드 × 설정 3종(기본 / 외부 라이브러리 포함 /
 * max-depth 4)의 Action 분석 출력 전부를 GOLDEN_DIR 에 쓴다. 바꾸기 전 커밋(git worktree)과 지금 코드에서 각각 돌려 diff -r 로
 * 비교한다 (사용자: 기존 기능은 테스트 빡세게). 출력의 절대 경로(sourceRoot)만 다르므로 비교 전에 경로를 지운다.
 *   GOLDEN_DIR=/tmp/new ./gradlew test --tests analyzer.GoldenDumpTest --rerun-tasks
 */
class GoldenDumpTest {
    @Test
    void dump() throws Exception {
        String out = System.getenv("GOLDEN_DIR");
        if (out == null) return;
        Path base = Path.of("src/test/resources/sample-project/src/main/java/com/example/action");
        Pattern p = Pattern.compile("^    public [\\w<>\\[\\], ]+ (\\w+)\\(", Pattern.MULTILINE);
        try (var files = Files.walk(base)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
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
                        cfg.output.flowchartSkeleton = true;
                        cfg.output.flowchartSvg = false;
                        if (v == 1) cfg.analysis.includeExternalLibraryCalls = true;
                        if (v == 2) cfg.analysis.maxDepth = 4;
                        AnalysisResult r;
                        try { r = Main.analyze(cfg); } catch (Throwable e) { Files.createDirectories(Path.of(out)); Files.writeString(Path.of(out, cls + "." + meth + "." + v + ".ERROR"), String.valueOf(e.getMessage())); continue; }
                        Path dir = Path.of(out, cls + "." + meth + "." + v);
                        try { new OutputWriter().write(r, cfg, dir); } catch (Throwable e) { Files.createDirectories(dir); Files.writeString(dir.resolve("WRITE_ERROR"), String.valueOf(e)); }
                    }
                }
            }
        }
    }
}
