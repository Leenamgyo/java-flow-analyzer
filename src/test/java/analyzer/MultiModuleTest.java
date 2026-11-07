package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 멀티 모듈: actions/src/main/java (Action) + lib/src/main/java (Service·Mapper·VO) */
class MultiModuleTest {
    static final String BASE = "src/test/resources/multi-module";
    static final String ACTIONS = BASE + "/actions/src/main/java";
    static final String LIB = BASE + "/lib/src/main/java";

    static AnalyzerConfig config() {
        AnalyzerConfig cfg = new AnalyzerConfig();
        cfg.target.sourceRoots = List.of(ACTIONS, LIB);
        cfg.target.className = "MemberAction";
        cfg.target.entryMethod = "execute";
        cfg.analysis.expandRoles = List.of("Service", "Mapper");
        cfg.analysis.linkSingleImplementation = true;
        return cfg;
    }

    @Test
    void actionInOneModuleReachesServiceAndMapperInAnother() {
        AnalysisResult r = Main.analyze(config());
        assertEquals(2, r.sourceRoots.size());

        MethodInfo impl = Fixtures.method(r, "MemberServiceImpl", "findMember(MemberVO)");
        assertTrue(impl.analyzed, "lib 모듈 구현 본문 분석");
        assertTrue(impl.location.file().startsWith("lib/src/main/java/com/mm/service/"), impl.location.file());
        MethodInfo entry = r.method(r.entryMethodId);
        assertTrue(entry.location.file().startsWith("actions/src/main/java/com/mm/action/"), entry.location.file());

        CallSiteInfo find = r.callSites.stream().filter(s -> "findMember".equals(s.methodName)).findFirst().orElseThrow();
        assertEquals(ResolutionStatus.RESOLVED, find.resolution);
        assertNotNull(find.implementationMethodId, "유일 구현 후보 연결 (lib 모듈)");
        assertEquals(Expansion.EXPANDED, find.expansion);

        CallSiteInfo select = r.callSites.stream().filter(s -> "selectMember".equals(s.methodName)).findFirst().orElseThrow();
        assertEquals(ResolutionStatus.RESOLVED, select.resolution);
        assertEquals(Expansion.NO_BODY, select.expansion);

        // lib 모듈 VO 로의 바인딩
        assertTrue(r.bindings.stream().anyMatch(b -> "memberId".equals(b.field) && b.sources.stream().anyMatch(v -> v.display().contains("request.memberId"))),
                r.bindings.toString());
    }

    @Test
    void duplicateFqnAcrossModulesIsAmbiguousNotGuessed() {
        AnalysisResult r = Main.analyze(config());
        CallSiteInfo check = r.callSites.stream().filter(s -> "check".equals(s.methodName)).findFirst().orElseThrow();
        assertEquals(ResolutionStatus.AMBIGUOUS, check.resolution);
        assertEquals(2, check.candidates.size(), check.candidates.toString());
        assertTrue(r.notes.stream().anyMatch(n -> n.contains("중복 FQN") && n.contains("com.mm.util.DupUtil")), r.notes.toString());
    }

    @Test
    void singleRootOnlyCannotSeeOtherModule() {
        AnalyzerConfig cfg = config();
        cfg.target.sourceRoots = List.of(ACTIONS);
        AnalysisResult r = Main.analyze(cfg);
        assertTrue(r.methods().stream().noneMatch(m -> r.classOf(m).name.equals("MemberServiceImpl")));
    }

    @Test
    void outputInsideAnyRootIsRejectedAndOverlappingRootsAreRejected() {
        AnalyzerConfig cfg = config();
        cfg.output.directory = LIB + "/out";
        assertThrows(IllegalArgumentException.class, () -> Main.outputDir(cfg, "MemberAction"));
        AnalyzerConfig overlap = config();
        overlap.target.sourceRoots = List.of(BASE, LIB);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validate(overlap));
    }

    @Test
    void sourceRootsFromConfigFileAndYamlOutput(@TempDir Path tmp) throws Exception {
        Path conf = tmp.resolve("analyzer.yml");
        Files.writeString(conf, """
                target:
                  source-roots:
                    - %s
                    - %s
                  class: MemberAction
                  entry-method: execute
                analysis:
                  expand-roles: [Service, Mapper]
                  link-single-implementation: true
                output:
                  directory: %s
                """.formatted(Path.of(ACTIONS).toAbsolutePath(), Path.of(LIB).toAbsolutePath(), tmp.resolve("out")));
        assertEquals(0, Main.run(new String[]{"--config", conf.toString()}));
        String yaml = Files.readString(tmp.resolve("out/MemberAction/analysis.yaml"));
        assertTrue(yaml.contains("sourceRoots:"), yaml);
        assertTrue(yaml.contains("lib/src/main/java/com/mm/service/MemberServiceImpl.java"));
    }
}
