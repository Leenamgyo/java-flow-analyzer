package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.serializer.OutputWriter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 같은 입력이면 출력 파일이 바이트 단위로 같다 (ID 순서 결정성). 회귀 비교(GoldenDumpTest)에서 찾은 문제:
 * 셋업 클래스 자기 필드 대입을 IdentityHashMap 순서로 꺼내 AppDetailAction.execute 의 바인딩 ID 가 실행마다 바뀌었고,
 * flowchart.html 의 글자색 표(Map.of)도 실행마다 순서가 달랐다. 한 JVM 안에서도 객체 주소가 달라 재현된다.
 */
class DeterminismTest {

    @ParameterizedTest
    @CsvSource({"AppDetailAction,execute,4", "AppDetailAction,execute,1", "ScpAppListAction,execute,1", "EdgeAction,holderVariants,4"})
    void sameInputSameOutput(String clazz, String method, int depth, @TempDir Path tmp) throws Exception {
        String first = null;
        for (int i = 0; i < 4; i++) {
            AnalyzerConfig cfg = ConfigLoader.defaults();
            cfg.target.sourceRoot = Fixtures.SOURCE_ROOT;
            cfg.target.className = clazz;
            cfg.target.entryMethod = method;
            cfg.analysis.maxDepth = depth;
            cfg.output.flowchartSvg = false;
            Path dir = tmp.resolve("run" + i);
            new OutputWriter().write(Main.analyze(cfg), cfg, dir);
            StringBuilder all = new StringBuilder();
            for (String f : List.of("analysis.yaml", "flowchart.mmd", "mapping.md", "call-tree.txt", "flowchart.html")) {
                all.append(f).append('\n').append(Files.readString(dir.resolve(f)));
            }
            if (first == null) {
                first = all.toString();
            } else {
                assertEquals(first, all.toString(), "실행 " + i + " 결과가 첫 실행과 다름");
            }
            System.gc(); // 객체 주소(identity hash)가 바뀌게
        }
    }
}
