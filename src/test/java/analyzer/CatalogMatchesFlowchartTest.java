package analyzer;

import analyzer.catalog.CatalogKeys;
import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.model.AnalysisResult;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 카탈로그 uses = 흐름도 내용 (사용자: 흐름도의 노드를 그대로 옮겨서, 흐름도에 있는 내용이 모두 매칭되어야 — ODAction.login 의 아래 클래스가
 * 카탈로그에 안 나왔음). 모든 fixture 진입 메서드 × 내장 기본 조건: 흐름도 텍스트에 이름(…) 으로 보이는 프로젝트 메서드는 모두 uses 에,
 * uses 의 메서드는 흐름도에 보이거나 값 설정 줄(VO setter·getter → 프로퍼티 = 값)로 보인다.
 */
class CatalogMatchesFlowchartTest {

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        FlowchartSweepTest.ENTRIES.forEach(e -> out.add(Arguments.of(e[0], e[1])));
        out.add(Arguments.of("AppDetailAction", "execute"));
        return out.stream();
    }

    @ParameterizedTest(name = "{0}.{1}")
    @MethodSource("cases")
    void usesMatchFlowchart(String clazz, String method) {
        AnalyzerConfig cfg;
        try {
            cfg = ConfigLoader.defaults();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        cfg.target.sourceRoot = Fixtures.SOURCE_ROOT;
        cfg.target.className = clazz;
        cfg.target.entryMethod = method;
        AnalysisResult r = Main.analyze(cfg);
        String mmd = new FlowchartGenerator(FlowchartGenerator.roles(cfg.phases.logicRoles)).exclude(cfg.output.flowchartExclude).render(r, "TD");
        Set<String> uses = new TreeSet<>(CatalogKeys.uses(r, cfg));
        Set<String> missing = new TreeSet<>(), extra = new TreeSet<>();
        for (MethodInfo m : r.methods()) {
            if (r.isEntry(m.id) || m.origin != Origin.SOURCE) {
                continue;
            }
            boolean shown = mmd.contains(m.name + "(") || m.name.equals(r.classOf(m).name.replaceFirst(".*\\.", "")) && mmd.contains("new ");
            boolean binding = r.classOf(m).role == ClassRole.VO; // 값 설정 줄(프로퍼티 = 값)로 보임
            if (shown && !uses.contains(m.key) && r.callSites.stream().anyMatch(s -> m.id.equals(s.targetMethodId) || m.id.equals(s.implementationMethodId))) {
                missing.add(m.key);
            }
            if (!shown && !binding && uses.contains(m.key)) {
                extra.add(m.key);
            }
        }
        assertEquals(Set.of(), missing, "흐름도에 보이는데 카탈로그에 없음");
        assertEquals(Set.of(), extra, "카탈로그에 있는데 흐름도에 안 보임");
    }
}
