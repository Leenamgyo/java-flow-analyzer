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

/** Action 안에 선언한 클래스(이름 무관: 생성자 new ParamMgr(request) · 메서드 Paging.setup(request)) · CountryUtil 추적 · spec.md */
class RequestSetupTest {

    private static AnalysisResult analyze(List<String> expandClasses) {
        return Fixtures.analyze("AppDetailAction", "execute", c -> c.analysis.expandClasses = expandClasses);
    }

    @Test
    void classesInsideActionAreTrackedWithoutConfig() {
        AnalysisResult r = analyze(List.of()); // 설정 없이: Action 안 클래스는 이름과 무관하게 분석
        assertEquals(List.of("appId", "lang", "startNum"), r.parameters.stream().map(p -> p.name).toList());
        var ctor = r.callSites.stream().filter(s -> "new ParamMgr(request)".equals(s.expression)).findFirst().orElseThrow();
        assertEquals(Expansion.EXPANDED, ctor.expansion);
        assertEquals("ParamMgr(HttpServletRequest)", r.method(ctor.targetMethodId).signature);
        assertTrue(r.callSites.stream().filter(s -> s.methodName.equals("setup")).allMatch(s -> s.expansion == Expansion.EXPANDED));
        // Util 은 설정(expand-classes) 없으면 depth-limit
        assertTrue(r.callSites.stream().filter(s -> s.methodName.equals("resolveCountry"))
                .allMatch(s -> s.expansion == Expansion.DEPTH_LIMIT));
        // Action 밖 클래스의 new 는 호출로 만들지 않는다
        assertTrue(r.callSites.stream().noneMatch(s -> s.expression.startsWith("new ArrayList")));
    }

    @Test
    void constructorAndMethodSetupBindFields() {
        AnalysisResult r = analyze(List.of("CountryUtil"));
        assertTrue(Fixtures.method(r, "CountryUtil", "resolveCountry(String, String)").analyzed);
        assertTrue(Fixtures.method(r, "CountryUtil", "byLang(String)").analyzed); // 같은 클래스 확장

        // 생성자의 자기 필드 대입 = 바인딩, 객체는 new 를 받는 변수 pm
        ObjectInfo pm = r.objects.stream().filter(o -> "AppDetailAction.ParamMgr".equals(o.className)).findFirst().orElseThrow();
        assertEquals("pm", pm.variable);
        List<Binding> bs = r.bindingsOf(pm);
        assertEquals(List.of("appId", "page", "mcc", "lang"), bs.stream().map(b -> b.field).toList());
        assertEquals("request.appId", bs.get(0).sources.get(0).display());
        assertTrue(bs.get(3).conditional());
        assertEquals(List.of("unused"), pm.unassignedFields); // 요청 값이 아닌 대입(page·mcc)도 할당으로
        assertTrue(r.requestSpec.get(0).bindingIds.contains(bs.get(0).id));

        // 메서드 setup(request) 쪽: 객체는 수신 변수 paging
        ObjectInfo paging = r.objects.stream().filter(o -> "AppDetailAction.Paging".equals(o.className)).findFirst().orElseThrow();
        assertEquals("paging", paging.variable);
        assertEquals(List.of("startNum", "count"), r.bindingsOf(paging).stream().map(b -> b.field).toList());

        // 표시: 중첩 클래스 = 바깥 클래스 포함, 생성자 = 클래스 이름 (Action.ParamMgr / Action.ParamMgr.method)
        String tree = new analyzer.serializer.CallTreeWriter(true).render(r);
        assertTrue(tree.contains("AppDetailAction.ParamMgr(HttpServletRequest) ["), tree);
        assertTrue(tree.contains("✎ AppDetailAction.Paging (paging)"), tree);
        String mmd = new analyzer.mermaid.FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("[\"AppDetailAction.ParamMgr() «Other»\"]"), mmd);
        assertTrue(mmd.contains("[\"AppDetailAction.Paging.setup() «Other»\"]") || !mmd.contains("Paging.setup()"), mmd);
    }

    @Test
    void specIsOneConciseTable() {
        AnalysisResult r = analyze(List.of("CountryUtil"));
        assertEquals("""
                # 명세: AppDetailAction.execute()

                ## 1. Request Param 정의

                | 파라미터 | 타입 | 필수 | 기본값 | 설명 |
                |---|---|---|---|---|
                | appId | String | N | - |  |
                | lang | String | N | - | .trim() |
                | startNum | String | N | - | Integer.parseInt() |
                """, new SpecWriter().render(r));
    }

    @Test
    void defaultsTrackParamMgrAndCountryUtil(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("c.yml");
        Files.writeString(f, "target:\n  source-root: x\n  class: X\n");
        AnalyzerConfig cfg = ConfigLoader.load(f);
        assertEquals(List.of("CountryUtil", "ODAction"), cfg.analysis.expandClasses);
    }
}
