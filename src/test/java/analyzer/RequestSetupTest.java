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
    void specHasRequestParamDefinition() {
        AnalysisResult r = analyze(List.of("*Action.ParamMgr", "CountryUtil"));
        String md = new SpecWriter().render(r);
        assertTrue(md.startsWith("# 명세: AppDetailAction.execute()"), md);
        assertTrue(md.contains("## 1. Request Param 정의"), md);
        assertTrue(md.contains("| 1 | **`appId`** | String | 확인 불가 | - | - | `ParamMgr.appId` | ParamMgr.setup:31 (P1) |"), md);
        assertTrue(md.contains("`ParamMgr.lang` (조건부)"), md);
        assertTrue(md.contains("`CountryUtil.resolveCountry(pm.mcc, pm.lang)` (CALL2 :18)"), md); // 사용처
        // 요청 객체를 넘겼지만 본문을 보지 않은 호출 = 수동 확인
        assertTrue(md.contains("`StringUtil.getParameter(request, \"page\", \"1\")` — ParamMgr.setup:32"), md);
    }

    @Test
    void defaultsTrackParamMgrAndCountryUtil(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("c.yml");
        Files.writeString(f, "target:\n  source-root: x\n  class: X\n");
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertEquals(List.of("*Action.ParamMgr", "CountryUtil"), cfg.analysis.expandClasses);
    }
}
