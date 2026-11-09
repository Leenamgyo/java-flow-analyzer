package analyzer;

import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 흐름도 규칙 경계 사례 회귀 테스트 — fixture EdgeAction (FlowchartSweepTest 에서 발견한 문제들) */
class FlowchartEdgeCaseTest {

    static CallSiteInfo call(AnalysisResult r, String expression) {
        return r.callSites.stream().filter(c -> c.expression.equals(expression)).findFirst()
                .orElseThrow(() -> new AssertionError("call not found: " + expression));
    }

    static ControlBlock block(AnalysisResult r, BlockType t) {
        return r.blocks.stream().filter(b -> b.type == t).findFirst().orElseThrow();
    }

    @Test
    void stateBlockHoldsOnlyVariableChanges() {
        // n = n - 1; touch(); — 상태 변화는 소스의 값 변경(=)만, void 호출 touch() 는 일반 호출 노드(==> 메서드)
        AnalysisResult r = Fixtures.analyze("EdgeAction", "nestedLoops");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("[\"상태 변화<br/>n = n - 1 · n v2\"]"), mmd);
        assertTrue(mmd.contains("CALL4[\"#35;4 touch()\"]"), mmd);
        assertFalse(mmd.contains(" · this v"), mmd); // 수신 객체 버전 표시 없음
    }

    @Test
    void analyzedSettersStayBindingLines() {
        // max-depth 가 깊어 setter 본문까지 분석해도 setter 는 바인딩 줄 (기존: ▸ vo.setKeyword(…) 바인딩 함수로 오인, 값 줄 누락)
        AnalysisResult r = Fixtures.analyze("CaseAction", "voBinding", c -> c.analysis.maxDepth = 4);
        CallSiteInfo setter = call(r, "vo.setKeyword(request.getParameter(\"keyword\"))");
        assertEquals(analyzer.model.Expansion.EXPANDED, setter.expansion);
        assertFalse(FlowchartGenerator.bindingFunctions(r).contains(setter.targetMethodId));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("#35;2 keyword = request.keyword"), mmd);
        assertFalse(mmd.contains("▸ #35;2 vo.setKeyword"), mmd);
    }

    @Test
    void builderStatementIsShownWithItsBindings() {
        // SearchVO built = SearchVO.builder()…build() — 바인딩 줄만 남고 문장이 사라지던 문제
        AnalysisResult r = Fixtures.analyze("CaseAction", "bindingKinds");
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("· SearchVO built<br/>#35;10 SearchVO built = SearchVO.builder().keyword(shopId).page(1).build()<br/>"
                + "#35;8 keyword = shopId<br/>#35;9 page = 1"), mmd);
    }

    @Test
    void bindingFunctionChainsAndRecursionNest() {
        // outer → middle → inner (값 함수 사슬), 같은 함수 두 번, 재귀 selfRef — 모두 바인딩 블록 안 중첩 (기존: 각각 상자)
        AnalysisResult r = Fixtures.analyze("EdgeAction", "bindingChain");
        var fns = FlowchartGenerator.bindingFunctions(r);
        for (String name : List.of("outer", "middle", "inner", "selfRef")) {
            assertTrue(r.methods().stream().anyMatch(m -> m.name.equals(name) && fns.contains(m.id)), name);
        }
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String flat = FlowchartSweepTest.normalize(new FlowchartGenerator().render(r, "TD"));
        assertTrue(flat.contains(FlowchartSweepTest.strip("▸ #2 SearchVO a = outer(request) │ keyword = middle(request.q) "
                + "│ ▸ middle(request.q) │ │ return inner(v) │ │ ▸ inner(v) │ │ │ return v == null ? \"\" : v.trim() "
                + "│ sort = request.sort │ return vo ▸ #3 SearchVO b = outer(request)")), flat);
        assertTrue(flat.contains(FlowchartSweepTest.strip("│ ▸ selfRef(request, depth - 1) │ │ ↺ cycle")), flat);
        r.methods().stream().filter(m -> fns.contains(m.id)).forEach(m -> assertFalse(g.has("sg_" + m.id), m.name));
    }

    @Test
    void loopsReenterThroughConditionCalls() {
        // while (check()) { touch(); } — 반복할 때 check() 를 다시 부른다 (기존: 조건 노드로 바로 돌아가 check() 를 건너뜀)
        AnalysisResult r = Fixtures.analyze("EdgeAction", "whileWithCall");
        String mmd = new FlowchartGenerator().render(r, "TD");
        ControlBlock w = block(r, BlockType.WHILE);
        CallSiteInfo check = call(r, "check()");
        CallSiteInfo touch = call(r, "touch()");
        assertTrue(mmd.contains(check.id + " --> " + w.id), mmd);
        assertTrue(mmd.contains(w.id + " -->|\"loop\"| " + touch.id), mmd);
        assertTrue(mmd.contains(touch.id + " -->|\"next\"| " + check.id), mmd);
        // do {} while (check()) — 빈 본문: loop 는 check() 로, check() 를 건너뛰는 간선 없음
        AnalysisResult e = Fixtures.analyze("EdgeAction", "emptyLoops");
        String m2 = new FlowchartGenerator().render(e, "TD");
        ControlBlock d = block(e, BlockType.DO_WHILE);
        CallSiteInfo cond = e.callSites.stream().filter(c -> d.id.equals(c.controlBlockId)).findFirst().orElseThrow();
        assertTrue(m2.contains(d.id + " -->|\"loop\"| " + cond.id), m2);
        ControlBlock wh = block(e, BlockType.WHILE);
        assertFalse(m2.contains(wh.id + " -->|\"done\"| " + d.id), m2);
    }

    @Test
    void logVariantsMergeAndCatchLogsAreDrawn() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "logVariants");
        MermaidGraph g = new FlowchartGenerator().build(r);
        CallSiteInfo info = call(r, "LOG.info(\"id=\" + request.getParameter(\"id\"))");
        for (String e : List.of("LOG.error(\"fail\", new RuntimeException())", "System.err.println(\"err\")")) {
            assertEquals(info.id, g.nodeOf(call(r, e).id), e);
        }
        // catch 안: printStackTrace + LOG.warn 한 블록
        assertEquals(g.nodeOf(call(r, "e.printStackTrace()").id), g.nodeOf(call(r, "LOG.warn(\"caught\", e)").id));
        // 조건식의 isDebugEnabled 는 로그 호출이 아니다 (라이브러리 호출이라 모델 밖) — 조건 노드 텍스트로만 보인다
        assertTrue(r.callSites.stream().noneMatch(c -> c.methodName.equals("isDebugEnabled")));
        assertTrue(new FlowchartGenerator().render(r, "TD").contains("{\"if (LOG.isDebugEnabled())\"}"));
    }

    @Test
    void knownLimitContinueAndBreakAreNotModeled() {
        // 모델에 continue/break 가 없어 흐름도는 다음 문장으로 잇는다 — 고치면 이 테스트를 바꿀 것 (현재 한계를 고정)
        AnalysisResult r = Fixtures.analyze("EdgeAction", "loopExits");
        assertTrue(r.blocks.stream().noneMatch(b -> b.type.name().equals("CONTINUE") || b.type.name().equals("BREAK")));
    }

    @Test
    void statementLevelLibraryCallsAreKept() {
        // if 본문의 extraInfo.add(...) 처럼 문장 전체가 JDK 호출이면 모델·흐름도에 남는다 (기존: 제외되어 if 본문이 비었음)
        AnalysisResult r = Fixtures.analyze("EdgeAction", "libraryOnlyStatements");
        CallSiteInfo add = call(r, "extraInfo.add(\"DELTA\")");
        assertTrue(add.conditional);
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("extraInfo.add(#quot;DELTA#quot;)"), mmd);
        // JDK 호출뿐인 삼항 문장도 사라지지 않는다
        assertTrue(mmd.contains("extraInfo.isEmpty() ? null : extraInfo.get(0)"), mmd);
    }

    @Test
    void dynamicMapKeyAndRequestNameShownAsCode() {
        // m.put(keyVO.getKeyword(), …) / request.getParameter(keyVO.getKeyword()): 이름이 식 — ?(…) 대신 코드 그대로 [식]
        AnalysisResult r = Fixtures.analyze("EdgeAction", "mapDynamicKey");
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("[keyVO.getKeyword()] = request.v"), mmd);
        assertTrue(mmd.contains("String w = " + MermaidGraph.fontOpen(MermaidGraph.TEXT_COLORS.get("req")) + "request[keyVO.getKeyword()]</font>"), mmd);
        assertFalse(mmd.contains("?("), mmd);
    }

    @Test
    void partiallyResolvedExternalCallIsNotUnresolvedColor() {
        // String uri = request.getRequestURI(): 대상 타입은 확정(시그니처만 모름) → 주황 점선(unresolved)이 아니라 external
        AnalysisResult r = Fixtures.analyze("EdgeAction", "externalRequestCall",
                c -> c.analysis.includeExternalLibraryCalls = true);
        CallSiteInfo uri = call(r, "request.getRequestURI()");
        assertEquals(analyzer.model.ResolutionStatus.PARTIALLY_RESOLVED, uri.resolution);
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.lines().anyMatch(l -> l.trim().matches("class (.*,)?" + uri.id + "(,.*)? external")), mmd);
        assertFalse(mmd.lines().anyMatch(l -> l.trim().matches("class (.*,)?" + uri.id + "(,.*)? unresolved")), mmd);
    }

    @Test
    void consecutivePlainCallsShareOneNode() {
        AnalysisResult r = Fixtures.analyze("EdgeAction", "plainCallRun");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String first = g.nodeOf(call(r, "extraInfo.add(\"A\")").id);
        for (String e : List.of("extraInfo.add(\"B\")", "v.trim()", "Long.parseLong(v)")) {
            assertEquals(first, g.nodeOf(call(r, e).id), e);
        }
        // touch()(상태 변화)가 끼면 새 노드
        assertFalse(first.equals(g.nodeOf(call(r, "extraInfo.add(\"C\")").id)));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains("#35;1 extraInfo.add(#quot;A#quot;)<br/>#35;2 extraInfo.add(#quot;B#quot;)<br/>"
                + "#35;3 String t = v.trim()<br/>#35;4 long n = Long.parseLong(v)\"]"), mmd);
    }
}
