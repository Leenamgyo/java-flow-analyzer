package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.config.ConfigLoader;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.phase.Phase;
import analyzer.phase.PhaseAnalyzer;
import analyzer.phase.PhaseModel;
import analyzer.phase.PhaseModel.Region;
import analyzer.phase.PhaseModel.Step;
import analyzer.phase.PhaseReportWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 단계 보기: 바인딩 › 체크 › 로직 › 후처리 (옆 디렉터리 ../java-flow-example + sample-project) */
class PhaseTest {

    /** 예제 프로젝트는 이 저장소 옆(../java-flow-example). 없으면 이 테스트들은 건너뛴다 */
    static final Path EXAMPLE_PROJECT = Path.of("../java-flow-example");

    static AnalyzerConfig example(String clazz) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(EXAMPLE_PROJECT),
                "예제 프로젝트 없음: " + EXAMPLE_PROJECT.toAbsolutePath().normalize());
        AnalyzerConfig cfg = ConfigLoader.load(Path.of("config/java-flow-example.yml"));
        cfg.target.className = clazz;
        return cfg;
    }

    static Step step(PhaseModel pm, String labelPrefix) {
        return pm.steps.stream().filter(s -> label(s).startsWith(labelPrefix)).findFirst()
                .orElseThrow(() -> new AssertionError("step not found: " + labelPrefix + " in "
                        + pm.steps.stream().map(PhaseTest::label).toList()));
    }

    static String label(Step s) {
        if (s.element instanceof CallSiteInfo c) {
            return c.expression;
        }
        if (s.element instanceof ControlBlock b) {
            return b.type.yamlName() + " " + b.condition;
        }
        return "binding";
    }

    @Test
    void orderRegisterSplitsIntoBindingCheckLogicPost() throws Exception {
        AnalyzerConfig cfg = example("OrderRegisterAction");
        AnalysisResult r = Main.analyze(cfg);
        PhaseModel pm = PhaseAnalyzer.analyze(r, cfg.phases);

        // 요청 읽기 = 바인딩, 첫 로직 전
        Step mode = step(pm, "ParamUtil.getString(request, Constants.PARAM_MODE");
        assertEquals(Phase.BINDING, mode.phase);
        assertEquals(Region.PRE, mode.region);
        // 로그인 사용자 조회: 결과가 바인딩 출처 → 값 조회(바인딩)
        Step login = step(pm, "checkLogin(request)");
        assertEquals(Phase.BINDING, login.phase);
        assertEquals("member", login.fetchVariable);
        // 종료형 분기 = 체크, Validator 호출 = 체크
        assertEquals(Phase.CHECK, step(pm, "if !agree").phase);
        assertEquals(Phase.CHECK, step(pm, "OrderValidator.validate(").phase);
        // 한 문장 안의 중첩 호출은 바깥 호출 하나로
        Step memo = step(pm, "order.setMemo(");
        assertEquals(3, memo.group.size());
        assertEquals(Phase.BINDING, memo.phase);
        // 로직: 계산 메서드 ~ Service 호출, 사이의 preparePayment 는 로직 중 바인딩
        assertEquals(Phase.LOGIC, step(pm, "calculateTotal(items)").phase);
        Step pay = step(pm, "preparePayment(");
        assertEquals(Region.LOGIC, pay.region);
        assertEquals(Phase.BINDING, pay.phase);
        assertEquals(Phase.LOGIC, step(pm, "order.setTotalAmount(total)").phase); // 로직 결과 값 설정
        Step register = step(pm, "orderService.registerOrder(");
        assertEquals(pm.lastLogic, register.index - 1);
        // 마지막 로직 뒤 = 후처리
        assertEquals(Phase.POST, step(pm, "OrderResultDTO.builder()").phase);
        assertEquals(Phase.END, pm.steps.get(pm.steps.size() - 1).phase);

        // 체크 목록: 바인딩 중(readItems·toQuantity) + Validator 본문 + 진입 메서드 분기
        List<PhaseModel.CheckItem> checks = pm.preChecks();
        assertTrue(checks.stream().anyMatch(c -> c.condition().equals("!agree")));
        assertTrue(checks.stream().anyMatch(c -> c.condition().equals("\"MODIFY\".equals(mode) › StringUtil.isEmpty(orderNo)")));
        assertTrue(checks.stream().anyMatch(c -> c.condition().equals("catch NumberFormatException")
                && c.context().contains("toQuantity")));
        assertEquals(3, checks.stream().filter(c -> c.context().equals("OrderValidator.validate()")).count());
        assertTrue(checks.stream().anyMatch(c -> c.condition().startsWith("loop: ") && c.condition().contains("item.getQuantity() <= 0")));

        // 흐름도: 요청 읽기 호출은 노드가 아니라 ① 바인딩 요약에, 로직은 상세 노드로
        FlowchartGenerator gen = new FlowchartGenerator(pm);
        MermaidGraph g = gen.build(r);
        assertTrue(g.validate().isEmpty(), g.validate().toString());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g, pm.suppressedCalls, pm.suppressedBlocks,
                FlowchartGenerator.collapsedHelpers(r, pm.logicRoles)));
        assertFalse(g.has(((CallSiteInfo) mode.element).id));
        assertTrue(g.has("PH_BIND") && g.has("PH_CHECK1") && g.has("PH_POST") && g.has("PH_INL1"));
        assertTrue(g.has(((CallSiteInfo) register.element).id));
        // 바인딩 요약에만 쓰인 메서드(readItems 등)는 subgraph 를 그리지 않음
        assertFalse(g.has("sg_" + ((CallSiteInfo) step(pm, "readItems(request)").element).targetMethodId));
        String mmd = gen.render(r, "TD");
        assertTrue(mmd.contains("subgraph sg_PH_BIND[\"① 바인딩\"]"));
        // 레이어: Service 구현은 Service·Biz 레이어, calculateTotal(Service·DAO 호출 없음)은 호출 노드만
        CallSiteInfo calc = (CallSiteInfo) step(pm, "calculateTotal(items)").element;
        assertFalse(g.has("sg_" + calc.targetMethodId));
        assertTrue(mmd.contains(calc.id + "[\"#35;21 long total = calculateTotal(items)\"]"), mmd); // 내부 함수 = 코드 한 줄, 선언은 꼬리표 없음
        assertTrue(mmd.contains("subgraph sg_LAYER_DAO") || !mmd.contains("«DAO»"));
        assertTrue(mmd.indexOf("subgraph sg_LAYER_SERVICE") < mmd.indexOf("subgraph sg_M27["), mmd); // registerOrder 구현
        assertTrue(mmd.contains("요청 파라미터 (19)<br/>mode<br/>orderNo<br/>agreeTerms<br/>channel<br/>"), mmd);
        assertTrue(mmd.contains("VO (3)<br/>OrderVO order<br/>OrderItemVO item<br/>DeliveryVO d"));
        // checkLogin 은 값 조회(바인딩) 한 줄로 끝 — 내부로 들어가지 않고, ② 체크에도 없음
        assertTrue(mmd.contains("조회: member = checkLogin(request)\"]"), mmd);
        assertTrue(pm.preChecks().stream().noneMatch(c -> c.context().contains("checkLogin")));
        assertEquals("!agree", pm.preChecks().get(0).condition());

        String md = new PhaseReportWriter().render(r, pm);
        assertTrue(md.contains("바인딩(L47–50) → 체크(L52) → 바인딩(L58–74) → 체크(L77) → 로직(L78–87) → 바인딩(L88) → 로직(L90–104) → 후처리(L105–127) → 종료(L132)"), md);
    }

    @Test
    void mainFlowchartMergesConsecutiveRequestReads() throws Exception {
        AnalysisResult r = Main.analyze(example("OrderRegisterAction"));
        String entry = r.entryMethodId;
        List<CallSiteInfo> reads = r.callSitesOf(entry).stream()
                .filter(s -> s.order >= 2 && s.order <= 4).toList(); // mode · orderNo · agreeTerms
        assertEquals(3, reads.size());
        assertTrue(reads.stream().allMatch(s -> !r.parametersAt(s.id).isEmpty()));
        FlowchartGenerator gen = new FlowchartGenerator();
        MermaidGraph g = gen.build(r);
        assertTrue(g.validate().isEmpty(), g.validate().toString());
        assertTrue(FlowchartGenerator.checkMapping(r, g).isEmpty());
        reads.forEach(s -> assertEquals(reads.get(0).id, g.nodeOf(s.id)));
        String mmd = gen.render(r, "TD");
        // 코드 형식: 변수 = request.이름 ?: 기본값
        assertTrue(mmd.contains(reads.get(0).id + "[\"요청 파라미터<br/>#35;2 String mode = request.mode ?: #quot;NEW#quot;<br/>"
                + "#35;3 String orderNo = request.orderNo<br/>#35;4 boolean agree = request.agreeTerms ?: false\"]"), mmd);
        assertFalse(mmd.contains(reads.get(1).id + "["));
        // 연속된 setter → VO 값 설정 노드 1개 (필드 ← 출처), setter 자체는 노드가 아님
        // VO 값 설정: 변수명·타입 없이 "프로퍼티 = 값" (클래스는 노드 제목)
        assertTrue(mmd.contains("[\"VO 값 설정 · OrderVO<br/>#35;6 memberId = member.getMemberId()<br/>#35;7 orderType = mode<br/>"
                + "#35;9 channel = request.channel<br/>#35;12 memo = StringUtil.trimToEmpty(request.memo)\"]"), mmd);
        assertTrue(mmd.contains("#35;27 totalAmount = total  ⟳ 변경 2회"), mmd); // 값이 바뀌는 변수
        // 조건식: && / || 는 줄 맨 앞, for-each 는 ':' 앞뒤 두 줄, 꼬리표(depth-limit 등) 없음
        assertTrue(mmd.contains("{\"if (couponCode != null<br/>&& !couponCode.isEmpty())\"}"), mmd);
        assertTrue(mmd.contains("{\"for (OrderItemVO item<br/>: items)\"}"), mmd);
        assertFalse(mmd.contains("depth-limit"), mmd);
        // 조건식 안의 호출은 조건 노드에만 (중복 노드 없음), 요청 읽기는 request.이름
        assertTrue(mmd.contains("{\"if (request.sendSms ?: false)\"}"), mmd);
        assertFalse(mmd.contains("(boolean) request.sendSms"), mmd);
        CallSiteInfo isEmpty = r.callSites.stream().filter(c -> c.expression.equals("StringUtil.isEmpty(orderNo)")).findFirst().orElseThrow();
        assertFalse(mmd.contains(isEmpty.id + "[\""), mmd);
        assertTrue(g.nodeOf(isEmpty.id).startsWith("IF"), g.nodeOf(isEmpty.id));
        r.bindings.stream().filter(b -> b.callSiteId != null).forEach(b -> assertFalse(g.has(b.callSiteId)));
        // 생성자 바인딩(호출 없음)도 위치 순으로 포함, 바인딩 메서드 내부 바인딩은 호출 위치에
        assertTrue(mmd.contains("[\"VO 값 설정 · NotifyCommand<br/>memberId = member.getMemberId()<br/>phone = request.smsPhone\"]"), mmd);
        // 변수 이력: 선언(호출 결과) → 쿠폰 차감 → 포인트 차감
        assertTrue(mmd.contains("#35;21 long total = calculateTotal(items)\""), mmd);   // 선언: 값 변경 아님 (꼬리표 없음)
        assertTrue(mmd.contains("[\"값 변경<br/>total -= usePoint · total v3\"]"), mmd);     // 선언 = v1, 변경마다 +1
        assertTrue(mmd.startsWith(MermaidGraph.INIT + "\nflowchart TD\n"));
    }

    @Test
    void valueLookupEndsAsOneBindingLine() throws Exception {
        AnalysisResult r = Main.analyze(example("OrderRegisterAction"));
        CallSiteInfo login = r.callSites.stream().filter(s -> s.methodName.equals("checkLogin")).findFirst().orElseThrow();
        // 내부로 들어가는 건 Service·DAO 뿐 — 상위 클래스 checkLogin 은 본문을 분석하지 않고 바인딩 한 줄
        assertFalse(r.method(login.targetMethodId).analyzed);
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        assertFalse(g.has("sg_" + login.targetMethodId));
        assertTrue(g.edges().stream().noneMatch(e -> e.from().equals(login.id) && e.style() == MermaidGraph.EdgeStyle.CALL));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(mmd.contains(login.id + "[\"#35;1 MemberVO member = checkLogin(request)\"]"), mmd);
        assertFalse(mmd.contains("실패:"), mmd);
    }

    @Test
    void mainFlowchartDetailFollowsLogicRolesSetting() throws Exception {
        AnalysisResult r = Main.analyze(example("OrderRegisterAction"));
        String selectPoint = r.methods().stream().filter(m -> m.name.equals("selectPoint") && m.analyzed)
                .findFirst().orElseThrow().id;
        // 기본(Service·Biz·DAO·Mapper…): DAO 메서드도 세부로
        assertTrue(new FlowchartGenerator().build(r).has("sg_" + selectPoint));
        // 설정에서 Service·Biz 만 로직 역할로 → DAO 메서드는 세부 없이 호출 한 줄
        var roles = FlowchartGenerator.roles(List.of("Service", "Biz"));
        MermaidGraph g = new FlowchartGenerator(roles).build(r);
        assertFalse(g.has("sg_" + selectPoint));
        assertEquals(List.of(), g.validate());
    }

    @Test
    void phaseViewDrawsEveryCallOfChainedStatement() {
        // SdkClient.getInstance().push("y") — 안쪽 getInstance() 도 노드로 (기존: "호출 노드 누락")
        AnalysisResult r = Fixtures.analyze("CaseAction", "unresolvedCalls");
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        new FlowchartGenerator(pm).render(r, "TD");
    }

    @Test
    void flowchartSplitsPerMethodWithOverviewAndCombinedMarkdown() throws Exception {
        AnalysisResult r = Main.analyze(example("OrderRegisterAction"));
        List<FlowchartGenerator.Part> parts = new FlowchartGenerator().renderSplit(r, "TD", "dagre");
        // 00-overview + 그리는 메서드마다 1개 (진입 + Service·Biz·DAO 세부)
        assertEquals("00-overview.mmd", parts.get(0).file());
        assertTrue(parts.get(1).file().startsWith("01-M1-OrderRegisterAction.execute"), parts.get(1).file());
        MermaidGraph full = new FlowchartGenerator().build(r);
        long methodBoxes = r.methods().stream().filter(m -> full.has("sg_" + m.id)).count();
        assertEquals(methodBoxes + 1, parts.size());
        // overview: 메서드 노드 + 호출 관계만
        assertTrue(parts.get(0).mermaid().contains("M1 ==>"), parts.get(0).mermaid());
        // 진입 파일: Service 로 넘어가는 호출은 ▶ 안내 노드에서 끝, 다른 메서드 상세는 없음
        String entry = parts.get(1).mermaid();
        String register = parts.stream().filter(p -> p.file().contains("OrderServiceImpl.registerOrder")).findFirst().orElseThrow().file();
        assertTrue(entry.contains("▶ OrderServiceImpl.registerOrder(") && entry.contains(register), entry);
        assertFalse(entry.contains("subgraph sg_M27"), entry);
        // 합본: 목차 + 파트마다 mermaid 블록
        String md = FlowchartGenerator.combinedMarkdown("OrderRegisterAction.execute()", parts);
        assertEquals(parts.size(), md.split("```mermaid", -1).length - 1);
        assertTrue(md.contains("- [M1 OrderRegisterAction.execute()](#m1-orderregisteractionexecute)"), md);
    }

    @Test
    void declarationWithOnlyUtilCallsIsOneBindingLine() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "statementBinding", c -> c.analysis.expandRoles = List.of("Biz"));
        List<CallSiteInfo> calls = r.callSitesOf(r.entryMethodId).stream()
                .filter(s -> s.statement != null && s.statement.startsWith("boolean checkMandatoryParameterInQA")).toList();
        assertEquals(3, calls.size()); // isQAStore, isNotEmpty, "1".equals (문장의 가장 바깥 JDK 호출도 모델에)
        assertFalse(calls.get(0).statement.contains("필수"), calls.get(0).statement); // 주석 제외
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        assertEquals(g.nodeOf(calls.get(0).id), g.nodeOf(calls.get(1).id)); // 한 줄 (요청 파라미터와 같은 바인딩 블록)
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertTrue(g.labelOf(g.nodeOf(calls.get(0).id)).equals("바인딩"), g.labelOf(g.nodeOf(calls.get(0).id)));
        assertTrue(mmd.contains("</span><br/>#35;4–6 boolean checkMandatoryParameterInQA = com.example.util.ODSUtil.isQAStore(shopID)<br/>"
                + "#gt; && com.example.util.StringUtil.isNotEmpty(sdkVer) && #quot;1#quot;.equals(pd)\"]"), mmd);
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        Step st = pm.steps.stream().filter(x -> x.group.contains(calls.get(0))).findFirst().orElseThrow();
        assertEquals(3, st.group.size());
        assertEquals(Phase.BINDING, st.phase);
        assertEquals(PhaseModel.Region.PRE, st.region);
        new FlowchartGenerator(pm).render(r, "TD");
    }

    @Test
    void callsInsideArgumentsAreNotDrawn() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "statementBinding", c -> c.analysis.expandRoles = List.of("Biz"));
        CallSiteInfo inLog = r.callSites.stream().filter(c -> c.expression.contains("getParam(request, \"url\")")).findFirst().orElseThrow();
        CallSiteInfo inLogic = r.callSites.stream().filter(c -> c.expression.equals("com.example.util.ODSUtil.isQAStore(shopID)")
                && c.inArgument).findFirst().orElseThrow();
        assertTrue(inLog.inArgument);
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        // 인자 안 호출은 노드 없음 (요청 읽기는 예외 — getParam 은 규칙이 없으니 일반 유틸 호출)
        assertFalse(g.has(inLog.id));
        assertFalse(g.has(inLogic.id));
        // 바깥 로직 호출(noticeBiz.list)은 그대로
        CallSiteInfo list = r.callSites.stream().filter(c -> c.expression.startsWith("noticeBiz.list(com.example")).findFirst().orElseThrow();
        assertTrue(g.has(list.id));
        // 모델·트리에는 그대로
        assertTrue(new analyzer.serializer.CallTreeWriter(true).render(r).contains("(" + inLog.id + ")"));
    }

    @Test
    void bindingNodeIsNeverEmpty() {
        // var client = SdkClient.getInstance(); 처럼 값 준비만 있는 ① 바인딩도 내용이 있어야 (기존: PH_BIND[""] 파싱 오류)
        AnalysisResult r = Fixtures.analyze("CaseAction", "unresolvedCalls");
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        String mmd = new FlowchartGenerator(pm).render(r, "TD");
        assertFalse(mmd.contains("[\"\"]"), mmd);
        assertTrue(mmd.contains("값 준비 (1)<br/>var client = SdkClient.getInstance()"), mmd);
    }

    @Test
    void checkMethodWithRequestReadInConditionIsCheck() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute",
                c -> c.analysis.expandRoles = List.of("Service", "Biz", "DAO"));
        PhaseModel pm = PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases());
        // if (request.getParameter("mcc") == null) throw … 만 있는 메서드 → 체크 (조건식 안 요청 읽기는 체크의 일부)
        assertEquals(Phase.CHECK, step(pm, "validateParameters(request)").phase);
        assertEquals(Phase.BINDING, step(pm, "createCommand(").phase);
        assertEquals(Phase.LOGIC, step(pm, "scpAppListBiz.getAppList(cmd)").phase);
        assertEquals(1, pm.preChecks().size());
        // 요청 객체를 넘기는 근거 없는 호출(세션 조회) = 요청 컨텍스트 값 조회 → 바인딩
        Step login = step(pm, "getLoginUserId(request)");
        assertEquals(Phase.BINDING, login.phase);
        assertEquals("userId", login.fetchVariable);
        assertTrue(login.reason.startsWith("요청 컨텍스트 값 조회"), login.reason);
    }

    @Test
    void phasesConfigRejectsUnknownRole() {
        AnalyzerConfig cfg = Fixtures.config("ScpAppListAction", "execute");
        cfg.phases.logicRoles = List.of("Servis");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ConfigLoader.validate(cfg));
    }
}
