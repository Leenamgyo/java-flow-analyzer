package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Util 이 빈 이름(Wcommon.getBean("productInfoBiz"))으로 Biz 를 꺼내 부르는 경우 (사용자 요구: 의존 관계가 안 보임).
 * 내장 기본 조건 그대로 — Util 은 max-depth 밖이지만 본문에 빈 조회가 있어 분석된다. fixture util/ProductUtil · common/Wcommon.
 */
class UtilBeanTest {

    static AnalysisResult analyze() throws Exception {
        AnalyzerConfig cfg = ConfigLoader.defaults();
        cfg.target.sourceRoot = Fixtures.SOURCE_ROOT;
        cfg.target.className = "EdgeAction";
        cfg.target.entryMethod = "utilBeans";
        return Main.analyze(cfg);
    }

    static CallSiteInfo call(AnalysisResult r, String utilMethod, String startsWith) {
        return r.callSites.stream().filter(c -> r.method(c.callerMethodId).name.equals(utilMethod) && c.expression.startsWith(startsWith))
                .findFirst().orElseThrow();
    }

    static String impl(AnalysisResult r, CallSiteInfo c) {
        MethodInfo m = r.method(c.expansionTargetId());
        return r.classOf(m).name + "." + m.name;
    }

    @Test
    void beanNameDecidesImplementation() throws Exception {
        AnalysisResult r = analyze();
        // 변수로 받아 쓰기 / 바로 캐스팅 — 인터페이스 구현이 2개여도 빈 이름이 @Service("productInfoBiz") 하나를 정한다
        for (String m : new String[]{"info", "infoInline"}) {
            CallSiteInfo c = call(r, m, m.equals("info") ? "biz.getInfo" : "((ProductInfoBiz)");
            assertEquals("ProductInfoBizImpl.getInfo", impl(r, c));
            assertEquals("BEAN_NAME", c.implementationBasis);
            assertTrue(c.implementationReason.contains("@Service(\"productInfoBiz\")"), c.implementationReason);
        }
        // Biz 구현 안의 DAO 까지
        assertTrue(r.callSites.stream().anyMatch(c -> c.expression.equals("productDAO.select(id)") && c.expansion == Expansion.EXPANDED));
    }

    @Test
    void reflectionInvokeBecomesCall() throws Exception {
        AnalysisResult r = analyze();
        CallSiteInfo c = call(r, "infoReflect", "bean.getClass().getMethod(\"getInfo\"");
        assertEquals("REFLECTION", c.resolutionBasis);
        assertEquals("ProductInfoBizImpl.getInfo", impl(r, c));
    }

    @Test
    void xmlBeanAndUnknownName() throws Exception {
        AnalysisResult r = analyze();
        CallSiteInfo legacy = call(r, "legacy", "((ProductInfoBiz)");
        assertEquals("ProductInfoCacheBiz.getInfo", impl(r, legacy)); // <bean id="legacyProductBiz" class="…ProductInfoCacheBiz">
        assertTrue(legacy.implementationReason.contains("context-biz.xml"), legacy.implementationReason);
        CallSiteInfo unknown = call(r, "unknown", "((ProductInfoBiz)");
        assertNull(unknown.implementationMethodId); // 정의를 못 찾으면 잇지 않고 사유만
        assertTrue(unknown.implementationReason.contains("noSuchBiz"), unknown.implementationReason);
    }
}
