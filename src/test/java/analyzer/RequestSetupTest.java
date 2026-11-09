package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.Expansion;
import analyzer.model.ObjectInfo;
import analyzer.serializer.SpecWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 요청 셋업 중첩 클래스(XxxAction.ParamMgr.setup(request)) · 로직 많은 Util(CountryUtil) 추적 · spec.md Request Param 정의 */
class RequestSetupTest {

    private static AnalysisResult analyze(List<String> expandClasses) {
        return Fixtures.analyze("AppDetailAction", "execute", c -> c.analysis.expandClasses = expandClasses);
    }

    @Test
    void withoutExpandClassesParamMgrIsDepthLimit() {
        AnalysisResult r = analyze(List.of());
        assertTrue(r.parameters.isEmpty());
        assertTrue(r.callSites.stream().filter(s -> s.methodName.equals("setup"))
                .allMatch(s -> s.expansion == Expansion.DEPTH_LIMIT));
    }

    @Test
    void paramMgrAndCountryUtilAreTracked() {
        AnalysisResult r = analyze(List.of("*Action.ParamMgr", "CountryUtil"));
        assertEquals(List.of("appId", "lang"), r.parameters.stream().map(p -> p.name).toList());
        assertTrue(Fixtures.method(r, "CountryUtil", "resolveCountry(String, String)").analyzed);
        assertTrue(Fixtures.method(r, "CountryUtil", "byLang(String)").analyzed); // 같은 클래스 확장

        // ParamMgr 자기 필드 대입 = 바인딩, 객체는 호출자 변수 pm
        ObjectInfo pm = r.objects.stream().filter(o -> "ParamMgr".equals(o.className)).findFirst().orElseThrow();
        assertEquals("pm", pm.variable);
        List<Binding> bs = r.bindingsOf(pm);
        assertEquals(List.of("appId", "page", "mcc", "lang"), bs.stream().map(b -> b.field).toList());
        assertEquals("request.appId", bs.get(0).sources.get(0).display());
        assertTrue(bs.get(3).conditional());
        assertEquals(List.of("unused"), pm.unassignedFields); // 요청 값이 아닌 대입(page·mcc)도 할당으로
        assertTrue(r.requestSpec.get(0).bindingIds.contains(bs.get(0).id));
    }

    @Test
    void specIsOneConciseTable() {
        AnalysisResult r = analyze(List.of("*Action.ParamMgr", "CountryUtil"));
        assertEquals("""
                # 명세: AppDetailAction.execute()

                ## 1. Request Param 정의

                | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
                |---|---|---|---|---|
                | appId | String | 확인 불가 | - |  |
                | lang | String | 확인 불가 | - | 조건 l != null · .trim() |
                """, new SpecWriter().render(r));
    }

    @Test
    void defaultsTrackParamMgrAndCountryUtil(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("c.yml");
        Files.writeString(f, "target:\n  source-root: x\n  class: X\n");
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertEquals(List.of("*Action.ParamMgr", "CountryUtil"), cfg.analysis.expandClasses);
    }
}
