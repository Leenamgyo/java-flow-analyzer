package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 내장 기본 조건(analyzer-defaults.yml) < 프로젝트 설정 파일 < CLI */
class ConfigLoaderTest {

    @Test
    void defaultsAreBuiltIn() throws Exception {
        AnalyzerConfig cfg = ConfigLoader.defaults();
        assertEquals(1, cfg.analysis.maxDepth);
        assertEquals(List.of("Service", "Biz", "DAO", "Repository", "Mapper", "Validator"), cfg.analysis.expandRoles);
        assertTrue(cfg.analysis.linkSingleImplementation);
        assertTrue(cfg.phases.enabled);
        assertNull(cfg.target.entryMethod);
        assertEquals("output", cfg.output.directory);
        assertEquals("dagre", cfg.output.layout);
        assertTrue(analyzer.mermaid.MermaidGraph.init("elk").contains("\"layout\": \"elk\""));
        assertTrue(!analyzer.mermaid.MermaidGraph.init("dagre").contains("layout"));
    }

    @Test
    void projectFileOverridesOnlyGivenKeys(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("p.yml");
        Files.writeString(f, """
                target:
                  source-root: /any/project/src/main/java
                  class: FooAction
                analysis:
                  max-depth: 2
                  expand-roles: [Service]
                output:
                  direction: LR
                """);
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertEquals("FooAction", cfg.target.className);
        assertEquals(2, cfg.analysis.maxDepth);                    // 덮어씀
        assertEquals(List.of("Service"), cfg.analysis.expandRoles); // 목록은 교체
        assertTrue(cfg.analysis.linkSingleImplementation);         // 나머지는 기본값 유지
        assertTrue(cfg.analysis.includeBindings);
        assertEquals("LR", cfg.output.direction);
        assertEquals("output", cfg.output.directory);
        assertEquals(List.of("validate*", "check*"), cfg.phases.checkMethodNames);
    }

    @Test
    void unknownKeyIsStillAnError(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("p.yml");
        Files.writeString(f, "analysis:\n  max-dept: 2\n");
        assertThrows(Exception.class, () -> ConfigLoader.load(f));
    }

    @Test
    void misindentedTopLevelKeyGivesShortHint(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("p.yml");
        Files.writeString(f, """
                target:
                  class: X
                  request-parameter-rules:
                    - methods: [ { name: getX } ]
                """);
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(f));
        assertTrue(e.getMessage().contains("'request-parameter-rules' 는 최상위 키입니다"), e.getMessage());
        assertTrue(e.getMessage().contains("위치: target"), e.getMessage());
    }

    /** 다른 프로젝트 경로 + 특정 Action 만: 설정 파일 없이 CLI 로 */
    @Test
    void otherProjectPathWithCliOnly(@TempDir Path tmp) throws Exception {
        // --config 없이 --source-root → 기본 조건만 (config/analyzer.yml 의 샘플 설정은 적용되지 않음)
        assertEquals(0, Main.run(new String[]{
                "--source-root", Path.of(MultiModuleTest.ACTIONS).toAbsolutePath().toString(),
                "--source-root", Path.of(MultiModuleTest.LIB).toAbsolutePath().toString(),
                "--class", "MemberAction", "--output", tmp.resolve("out").toString(), "--catalog-dir", tmp.resolve("catalog").toString()}));
        Path dir = tmp.resolve("out/MemberAction");
        for (String name : List.of("analysis.yaml", "flowchart.mmd", "phase-flowchart.mmd", "phase-summary.md", "mapping.md")) {
            assertTrue(Files.isRegularFile(dir.resolve(name)), name);
        }
        // entry-method 미지정 → execute() 추론, 기본 expand-roles 로 Service 본문까지
        String yaml = Files.readString(dir.resolve("analysis.yaml"));
        assertTrue(yaml.contains("INFERRED_ACTION_EXECUTE"), yaml);
        assertTrue(yaml.contains("lib/src/main/java/com/mm/service/MemberServiceImpl.java"));
    }
}
