package analyzer;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import analyzer.model.RequestParameterInfo;
import analyzer.model.ResolutionStatus;
import analyzer.model.Visibility;
import analyzer.parser.AnalysisException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static analyzer.Fixtures.analyze;
import static analyzer.Fixtures.blocks;
import static analyzer.Fixtures.callsTo;
import static analyzer.Fixtures.method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyzerTest {

    @Nested
    class EntryAndMetadata {
        final AnalysisResult r = analyze("ScpAppListAction", "execute");

        @Test
        void entryMethodIsDepth0AndClassifiedAsAction() {
            MethodInfo entry = r.method(r.entryMethodId);
            assertEquals("execute(HttpServletRequest, HttpServletResponse)", entry.signature);
            assertEquals(0, entry.depth);
            assertEquals("Action", r.clazz(r.targetClassId).role.label());
            assertEquals(List.of("Exception"), entry.thrownTypes);
            assertTrue(entry.location.beginLine() > 0);
        }

        @Test
        void fourAccessModifiers() {
            assertEquals(Visibility.PUBLIC, method(r, "ScpAppListAction", "execute(HttpServletRequest, HttpServletResponse)").visibility);
            assertEquals(Visibility.PRIVATE, method(r, "ScpAppListAction", "validateParameters(HttpServletRequest)").visibility);
            assertEquals(Visibility.PROTECTED, method(r, "ScpAppListAction", "logEmpty(String)").visibility);
            assertEquals(Visibility.PACKAGE_PRIVATE, method(r, "ScpAppListAction", "audit(String, int)").visibility);
        }

        @Test
        void interfaceMethodIsImplicitlyPublicAbstractAndNotExpanded() {
            MethodInfo m = method(r, "ScpAppListBiz", "getAppList(AppListCommand)");
            assertEquals(Visibility.PUBLIC, m.visibility);
            assertTrue(m.modifiersImplicit);
            assertTrue(m.isAbstract);
            assertEquals(Expansion.NO_BODY, callsTo(r, m).get(0).expansion);
            // 구현체(ScpAppListBizImpl)는 추측하지 않으므로 결과에 없음
            assertTrue(r.classes().stream().noneMatch(c -> c.name.equals("ScpAppListBizImpl")));
        }

        @Test
        void referenceOnlyFilesAreNotIncluded() {
            List<String> names = r.classes().stream().map(c -> c.name).toList();
            assertFalse(names.contains("CaseAction"));
            assertFalse(names.contains("AppController"));
            assertFalse(names.contains("BizException"));
        }
    }

    @Nested
    class DepthAndExpansion {
        final AnalysisResult r = analyze("ScpAppListAction", "execute");

        @Test
        void externalClassIsDepthLimitedAndItsBodyNotAnalyzed() {
            MethodInfo getString = method(r, "RequestUtil", "getString(HttpServletRequest, String, String)");
            assertFalse(getString.analyzed);
            assertEquals(Expansion.DEPTH_LIMIT, getString.notAnalyzedReason);
            assertTrue(r.callSitesOf(getString.id).isEmpty());
            assertEquals(ResolutionStatus.RESOLVED, callsTo(r, getString).get(0).resolution);
        }

        @Test
        void sameClassExpansionKeepsRealDepth() {
            MethodInfo parse = method(r, "ScpAppListAction", "parseParameter(String)");
            assertTrue(parse.analyzed);
            assertEquals(2, parse.depth);
            assertEquals(2, callsTo(r, parse).get(0).depth);
        }

        @Test
        void sharedExpansionForSecondCallSite() {
            MethodInfo audit = method(r, "ScpAppListAction", "audit(String, int)");
            List<CallSiteInfo> sites = callsTo(r, audit);
            assertEquals(2, sites.size());
            assertEquals(1, audit.depth);
            CallSiteInfo expanded = sites.stream().filter(s -> s.expansion == Expansion.EXPANDED).findFirst().orElseThrow();
            CallSiteInfo shared = sites.stream().filter(s -> s.expansion == Expansion.SHARED).findFirst().orElseThrow();
            assertEquals(1, expanded.depth);
            assertEquals(expanded.id, shared.expandedAt);
        }

        @Test
        void maxDepth2ExpandsExternalClassBody() {
            AnalysisResult r2 = analyze("ScpAppListAction", "execute", c -> c.analysis.maxDepth = 2);
            MethodInfo getString = method(r2, "RequestUtil", "getString(HttpServletRequest, String, String)");
            assertTrue(getString.analyzed);
        }

        @Test
        void disablingSameClassExpansion() {
            AnalysisResult r2 = analyze("ScpAppListAction", "execute", c -> c.analysis.expandSameClassMethods = false);
            MethodInfo validate = method(r2, "ScpAppListAction", "validateParameters(HttpServletRequest)");
            assertFalse(validate.analyzed);
            assertTrue(r2.callSites.stream().allMatch(s -> s.depth == 1));
        }

        @Test
        void externalLibraryCallsIncludedWhenEnabled() {
            AnalysisResult r2 = analyze("ScpAppListAction", "execute", c -> c.analysis.includeExternalLibraryCalls = true);
            assertTrue(r2.callSites.stream().anyMatch(s -> s.methodName.equals("getParameter")
                    && s.resolution == ResolutionStatus.PARTIALLY_RESOLVED
                    && "javax.servlet.http.HttpServletRequest".equals(s.receiverType)));
            assertEquals(0, r2.excludedExternalCalls);
            assertTrue(r.excludedExternalCalls > 0);
        }
    }

    @Nested
    class RoleExpansionAndImplementationLink {
        final AnalysisResult r = analyze("ScpAppListAction", "execute", c -> {
            c.analysis.expandRoles = List.of("Service", "Biz", "DAO", "Mapper");
            c.analysis.linkSingleImplementation = true;
        });

        @Test
        void singleImplementationIsLinkedAndExpandedByRole() {
            MethodInfo decl = method(r, "ScpAppListBiz", "getAppList(AppListCommand)");
            MethodInfo impl = method(r, "ScpAppListBizImpl", "getAppList(AppListCommand)");
            CallSiteInfo site = callsTo(r, decl).get(0);
            assertEquals(decl.id, site.targetMethodId); // 선언 대상은 그대로
            assertEquals(impl.id, site.implementationMethodId);
            assertEquals("SINGLE_SOURCE_IMPLEMENTATION", site.implementationBasis);
            assertEquals(List.of("com.example.biz.ScpAppListBizImpl"), site.implementationCandidates);
            assertEquals(Expansion.EXPANDED, site.expansion);
            assertTrue(impl.analyzed);
            assertEquals(1, impl.depth);
            assertFalse(decl.analyzed);
            assertEquals(Expansion.NO_BODY, decl.notAnalyzedReason);
        }

        @Test
        void daoReachedThroughImplementationIsExpandedBeyondMaxDepth() {
            MethodInfo select = method(r, "ScpAppDAO", "selectApps(String)");
            CallSiteInfo site = callsTo(r, select).get(0);
            assertEquals(2, site.depth);
            assertEquals(method(r, "ScpAppListBizImpl", "getAppList(AppListCommand)").id, site.callerMethodId);
            assertTrue(select.analyzed);
        }

        @Test
        void utilOutsideExpandRolesStaysDepthLimited() {
            MethodInfo getString = method(r, "RequestUtil", "getString(HttpServletRequest, String, String)");
            assertFalse(getString.analyzed);
            assertEquals(Expansion.DEPTH_LIMIT, getString.notAnalyzedReason);
        }

        @Test
        void multipleImplementationsAreNotLinked() {
            AnalysisResult r2 = analyze("CaseAction", "multipleImplementations", c -> {
                c.analysis.expandRoles = List.of("Biz");
                c.analysis.linkSingleImplementation = true;
            });
            CallSiteInfo site = callsTo(r2, method(r2, "NoticeBiz", "list(String)")).get(0);
            assertNull(site.implementationMethodId);
            assertEquals("MULTIPLE_SOURCE_IMPLEMENTATIONS", site.implementationBasis);
            assertEquals(List.of("com.example.biz.NoticeBizCache", "com.example.biz.NoticeBizDb"), site.implementationCandidates);
            assertEquals(Expansion.NO_BODY, site.expansion);
            assertTrue(r2.classes().stream().noneMatch(c -> c.name.startsWith("NoticeBiz") && !c.name.equals("NoticeBiz")));
        }

        @Test
        void unknownRoleInConfigIsRejected() {
            var cfg = Fixtures.config("ScpAppListAction", "execute");
            cfg.analysis.expandRoles = List.of("Helper");
            assertThrows(IllegalArgumentException.class, () -> analyzer.config.ConfigLoader.validate(cfg));
        }
    }

    @Nested
    class Calls {
        @Test
        void overloadsAreDistinctMethods() {
            AnalysisResult r = analyze("CaseAction", "overload");
            MethodInfo f1 = method(r, "CaseAction", "format(String)");
            MethodInfo f2 = method(r, "CaseAction", "format(String, int)");
            assertFalse(f1.id.equals(f2.id));
            assertEquals(1, callsTo(r, f1).size());
            assertEquals(1, callsTo(r, f2).size());
        }

        @Test
        void ambiguousEntryMethodIsRejected() {
            AnalysisException e = assertThrows(AnalysisException.class, () -> analyze("CaseAction", "process"));
            assertTrue(e.getMessage().contains("오버로딩"));
            AnalysisResult r = analyze("CaseAction", "process", c -> c.target.entryMethodParameterTypes = List.of("int"));
            assertEquals("process(int)", r.method(r.entryMethodId).signature);
        }

        @Test
        void duplicateCallsShareOneMethodInfo() {
            AnalysisResult r = analyze("CaseAction", "duplicate");
            MethodInfo helper = method(r, "CaseAction", "helper()");
            assertEquals(3, callsTo(r, helper).size());
            assertEquals(1, r.methods().stream().filter(m -> m.name.equals("helper")).count());
            var rel = r.relations.stream().filter(x -> x.toMethodId.equals(helper.id) && x.fromMethodId.equals(r.entryMethodId))
                    .findFirst().orElseThrow();
            assertEquals(2, rel.callSiteIds.size());
            List<Integer> orders = r.callSitesOf(r.entryMethodId).stream().map(s -> s.order).toList();
            assertEquals(List.of(1, 2, 3), orders);
        }

        @Test
        void recursionIsCycle() {
            AnalysisResult r = analyze("CaseAction", "recursive");
            CallSiteInfo s = r.callSites.get(0);
            assertEquals(Expansion.CYCLE, s.expansion);
            assertTrue(r.relations.get(0).cycle);
        }

        @Test
        void mutualRecursionIsCycle() {
            AnalysisResult r = analyze("CaseAction", "mutualA");
            MethodInfo b = method(r, "CaseAction", "mutualB()");
            assertEquals(Expansion.EXPANDED, callsTo(r, b).get(0).expansion);
            CallSiteInfo back = r.callSitesOf(b.id).get(0);
            assertEquals(r.entryMethodId, back.targetMethodId);
            assertEquals(Expansion.CYCLE, back.expansion);
        }

        @Test
        void unresolvedAndPartialCalls() {
            AnalysisResult r = analyze("CaseAction", "unresolvedCalls");
            Map<String, List<CallSiteInfo>> byName = r.callSites.stream().collect(Collectors.groupingBy(s -> s.expression));
            // 와일드카드 import 로만 보이는 외부 타입 → 수신 타입만 확인 (출처 UNKNOWN)
            CallSiteInfo getInstance = byName.get("SdkClient.getInstance()").get(0);
            assertEquals(ResolutionStatus.PARTIALLY_RESOLVED, getInstance.resolution);
            assertEquals(Origin.UNKNOWN, getInstance.targetOrigin);
            // var 변수 → 타입 추론 불가
            CallSiteInfo push = byName.get("client.push(\"x\")").get(0);
            assertEquals(ResolutionStatus.UNRESOLVED, push.resolution);
            assertNull(push.targetMethodId);
            assertEquals(Expansion.NOT_APPLICABLE, push.expansion);
            // 메서드 체인 결과 타입 불명
            assertEquals(ResolutionStatus.UNRESOLVED, byName.get("SdkClient.getInstance().push(\"y\")").get(0).resolution);
            // 문자열 리터럴 수신 객체는 java.lang.String 으로 확정 (외부 라이브러리 → 기본 설정에서 제외)
            assertTrue(r.callSites.stream().noneMatch(x -> x.expression.startsWith("\"lit\".equals")));
            // 선언 없는 이름
            assertEquals(ResolutionStatus.UNRESOLVED, byName.get("undeclared.call()").get(0).resolution);
        }

        @Test
        void conditionalVsUnconditionalAndDeferred() {
            AnalysisResult r = analyze("CaseAction", "switchAndLoop");
            List<CallSiteInfo> calls = r.callSitesOf(r.entryMethodId);
            // items.forEach(…) 는 문장 자체가 JDK 호출이라 모델에 남는 무조건 호출, 그 밖은 조건부/지연
            assertTrue(calls.stream().filter(s -> !s.methodName.equals("forEach")).allMatch(s -> s.conditional || s.deferred));
            CallSiteInfo ternary = calls.stream().filter(s -> s.methodName.equals("compute") && s.controlBlockId == null)
                    .findFirst().orElseThrow();
            assertTrue(ternary.conditions.get(0).contains("? true"));
            CallSiteInfo shortCircuit = calls.stream().filter(s -> s.methodName.equals("check")).findFirst().orElseThrow();
            assertTrue(shortCircuit.conditions.get(0).contains("&&"));
            CallSiteInfo lambda = calls.stream().filter(s -> s.deferred).findFirst().orElseThrow();
            assertEquals("release", lambda.methodName);

            AnalysisResult r2 = analyze("ScpAppListAction", "execute");
            CallSiteInfo validate = callsTo(r2, method(r2, "ScpAppListAction", "validateParameters(HttpServletRequest)")).get(0);
            assertFalse(validate.conditional);
        }

        @Test
        void switchCases() {
            AnalysisResult r = analyze("CaseAction", "switchAndLoop");
            ControlBlock sw = blocks(r, BlockType.SWITCH).get(0);
            List<ControlBlock> cases = sw.childBlockIds.stream().map(r::block).toList();
            assertEquals(3, cases.size());
            assertEquals(List.of("2", "3"), cases.get(1).caseLabels);
            assertTrue(cases.get(2).isDefault);
            assertEquals(1, blocks(r, BlockType.FOREACH).size());
        }
    }

    @Nested
    class Exceptions {
        @Test
        void singleAndMultiCatchWithRethrowAndFinally() {
            AnalysisResult r = analyze("ScpAppListAction", "execute");
            ControlBlock t = blocks(r, BlockType.TRY).get(0);
            assertEquals(2, t.catchBlockIds.size());
            ControlBlock c1 = r.block(t.catchBlockIds.get(0));
            ControlBlock c2 = r.block(t.catchBlockIds.get(1));
            assertEquals(List.of("IllegalArgumentException"), c1.exceptionTypes);
            assertEquals(List.of("BizException", "IllegalStateException"), c2.exceptionTypes);
            assertEquals(1, c1.catchOrder);
            assertEquals(2, c2.catchOrder);
            assertTrue(c1.rethrows);
            assertTrue(c2.throwsNewException);
            assertEquals(t.id, c1.parentBlockId);
            ControlBlock fin = r.block(t.finallyBlockId);
            assertEquals(1, fin.callIds.size());
            // try 안의 return 은 finally 를 거친다
            ControlBlock ret = blocks(r, BlockType.RETURN).stream().filter(b -> "\"success\"".equals(b.expression)).findFirst().orElseThrow();
            assertEquals(List.of(fin.id), ret.executesFinally);
            // catch 안의 throw 는 같은 try 의 catch 에 잡히지 않고 전파
            ControlBlock rethrow = blocks(r, BlockType.THROW).stream().filter(b -> b.rethrow).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, rethrow.propagatesToCaller);
            assertEquals(List.of(fin.id), rethrow.executesFinally);
            // 호출 블록 연결
            CallSiteInfo create = callsTo(r, method(r, "ScpAppListAction", "createCommand(String, int)")).get(0);
            assertEquals(t.id, create.controlBlockId);
        }

        @Test
        void tryWithResourcesAndNestedTry() {
            AnalysisResult r = analyze("CaseAction", "tryWithResources");
            List<ControlBlock> tries = blocks(r, BlockType.TRY);
            assertEquals(2, tries.size());
            ControlBlock outer = tries.get(0);
            ControlBlock inner = tries.get(1);
            assertEquals(outer.id, inner.parentBlockId);
            assertEquals(outer.nestingDepth + 1, inner.nestingDepth);
            assertEquals(2, outer.resources.size());
            assertEquals("reader", outer.resources.get(0).variable());
            assertEquals(2, outer.resources.get(0).closeOrder());
            assertEquals(1, outer.resources.get(1).closeOrder());
            assertTrue(inner.resources.isEmpty());
            assertEquals(List.of("IOException"), r.block(inner.catchBlockIds.get(0)).exceptionTypes);
        }

        @Test
        void finallyContainingReturnAltersCompletion() {
            AnalysisResult r = analyze("CaseAction", "finallyOverridesReturn");
            ControlBlock fin = blocks(r, BlockType.FINALLY).get(0);
            assertTrue(fin.containsReturn);
            ControlBlock thr = blocks(r, BlockType.THROW).get(0);
            assertEquals(List.of(fin.id), thr.executesFinally);
            assertEquals(Boolean.TRUE, thr.propagatesToCaller);
        }

        @Test
        void returnWithFinally() {
            AnalysisResult r = analyze("CaseAction", "returnWithFinally");
            ControlBlock fin = blocks(r, BlockType.FINALLY).get(0);
            assertEquals(List.of(fin.id), blocks(r, BlockType.RETURN).get(0).executesFinally);
            assertFalse(fin.containsReturn);
        }

        @Test
        void throwMatchingUsesTypeHierarchy() {
            AnalysisResult r = analyze("CaseAction", "throwCases");
            Map<String, ControlBlock> byType = blocks(r, BlockType.THROW).stream().filter(b -> !b.rethrow)
                    .collect(Collectors.toMap(b -> b.exceptionTypes.get(0), b -> b));
            ControlBlock t = blocks(r, BlockType.TRY).get(0);
            String catchIAE = t.catchBlockIds.get(0);
            String catchRuntime = t.catchBlockIds.get(1);
            assertEquals(catchIAE, byType.get("IllegalArgumentException").caughtBy);
            assertEquals(catchRuntime, byType.get("IllegalStateException").caughtBy);   // JDK 계층
            ControlBlock biz = byType.get("BizException");                                // 소스 계층: Exception 하위
            assertNull(biz.caughtBy);
            assertEquals(Boolean.TRUE, biz.propagatesToCaller);
            assertEquals(ResolutionStatus.RESOLVED, biz.exceptionResolution);
        }
    }

    @Nested
    class RequestParameters {
        @Test
        void legacyParameters() {
            AnalysisResult r = analyze("ScpAppListAction", "execute");
            Map<String, RequestParameterInfo> byVar = r.parameters.stream().filter(p -> p.variable != null)
                    .collect(Collectors.toMap(p -> p.variable, p -> p));
            assertEquals("mcc", byVar.get("mcc").name);
            assertEquals("HttpServletRequest.getParameter", byVar.get("mcc").extraction);
            assertEquals("String[]", byVar.get("appIds").type);
            assertNull(byVar.get("all").name);
            assertEquals("ALL_PARAMETERS", byVar.get("all").nameResolution);
            assertEquals("deviceId", byVar.get("deviceId").name);
            assertEquals("CONSTANT", byVar.get("deviceId").nameResolution);
            assertEquals("\"none\"", byVar.get("deviceId").defaultValue);
            assertEquals("UNKNOWN", byVar.get("dynamic").name);
            assertEquals("locale", byVar.get("locale").name); // 상위 클래스 상수
            // 사용자 규칙에 없는 ParameterUtil 은 추출하지 않음 (Fixtures 규칙은 RequestUtil 만 등록)
            assertFalse(byVar.containsKey("startNum"));
            // 다른 메서드(validateParameters)의 getParameter 도 별도로 기록
            assertTrue(r.parameters.stream().anyMatch(p -> p.variable == null && "mcc".equals(p.name)));
        }

        @Test
        void springAnnotations() {
            AnalysisResult r = analyze("AppController", null);
            assertEquals("INFERRED_FRAMEWORK_MAPPING", r.entrySelection);
            assertEquals("Controller", r.clazz(r.targetClassId).role.label());
            Map<String, RequestParameterInfo> byVar = r.parameters.stream()
                    .collect(Collectors.toMap(p -> p.variable, p -> p));
            assertEquals("category", byVar.get("category").name);
            assertEquals("@PathVariable", byVar.get("category").extraction);
            assertEquals(Boolean.FALSE, byVar.get("mcc").required);
            assertEquals("\"450\"", byVar.get("mcc").defaultValue);
            assertEquals("PARAMETER_NAME", byVar.get("page").nameResolution);
            assertEquals("X-Device", byVar.get("device").name);
            assertEquals("@ModelAttribute", byVar.get("cmd").extraction);
            // 객체 바인딩은 개별 요청 파라미터가 아니므로 요청 스펙 항목에서 제외
            assertTrue(r.requestSpec.stream().noneMatch(e -> e.name.equals("cmd")));
            assertTrue(r.requestSpec.stream().anyMatch(e -> e.name.equals("category")));
        }
    }

    @Nested
    class Bindings {
        final AnalysisResult r = analyze("CaseAction", "voBinding");

        analyzer.model.Binding field(AnalysisResult res, String cls, String name) {
            return res.bindings.stream().filter(b -> b.field.equals(name) && res.object(b.objectId).className.equals(cls))
                    .findFirst().orElseThrow(() -> new AssertionError("no binding " + cls + "." + name));
        }

        List<String> sources(analyzer.model.Binding b) {
            return b.sources.stream().map(analyzer.model.ValueSource::display).toList();
        }

        @Test
        void setterBindingsTraceRequestSourcesAndTransforms() {
            assertEquals("request.keyword", sources(field(r, "SearchVO", "keyword")).get(0));
            var page = field(r, "SearchVO", "page");
            assertEquals(List.of("request.page"), sources(page));
            assertTrue(page.transforms.contains("Integer.parseInt()"), page.transforms.toString());
            var sort = field(r, "SearchVO", "sort");
            assertEquals(List.of("request.sort", "literal:date"), sources(sort));
            assertTrue(sort.reassigned);
            assertEquals(List.of("literal:LIST"), sources(field(r, "SearchVO", "mode")));
            assertEquals("always", analyzer.serializer.Labels.bindingCondition(field(r, "SearchVO", "mode")));
        }

        @Test
        void objectPassedAsArgumentIsSameObject() {
            assertEquals(1, r.objects.size());
            var url = field(r, "SearchVO", "URL");
            assertEquals("fill", r.method(url.methodId).name);
            assertEquals("request.url", url.sources.get(0).display());
            assertEquals(1, url.sources.get(0).via().size());
            assertTrue(url.transforms.contains(".trim()"));
            assertEquals("vo", r.objects.get(0).variable);
        }

        @Test
        void requestCallsAreKeptAndSpecLinksBindings() {
            assertTrue(r.parameters.stream().allMatch(p -> p.callSiteId != null));
            assertEquals(List.of("keyword", "page", "sort", "url"), r.requestSpec.stream().map(e -> e.name).toList());
            var sortSpec = r.requestSpec.stream().filter(e -> e.name.equals("sort")).findFirst().orElseThrow();
            assertEquals(List.of(field(r, "SearchVO", "sort").id), sortSpec.bindingIds);
        }

        @Test
        void allAssignmentKinds() {
            AnalysisResult k = analyze("CaseAction", "bindingKinds");
            var shop = k.bindings.stream().filter(b -> b.field.equals("shopId")).toList();
            assertEquals(List.of("CONSTRUCTOR", "SETTER", "SETTER"), shop.stream().map(b -> b.assignment).toList());
            assertEquals(List.of("\"1\".equals(pd)"), shop.get(2).conditions);
            assertEquals("literal:testShop", shop.get(2).sources.get(0).display());
            assertEquals("request.appId", field(k, "AppCommand", "appId").sources.get(0).display());
            assertEquals("FIELD", field(k, "AppCommand", "memo").assignment);

            var keyword = field(k, "SearchVO", "keyword");
            assertEquals("BUILDER", keyword.assignment);
            assertEquals(ResolutionStatus.PARTIALLY_RESOLVED, keyword.resolution); // builder 가 소스에 없음
            assertEquals("literal:1", field(k, "SearchVO", "page").sources.get(0).display());

            assertEquals("MAP_PUT", field(k, "Map<String,Object>", "shop").assignment);
            assertEquals("request.pd", field(k, "Map<String,Object>", "pd").sources.get(0).display()); // 상수 키 KEY_PD → "pd"

            var copy = k.bindings.stream().filter(b -> b.assignment.equals("COPY")).findFirst().orElseThrow();
            assertEquals("*", copy.field);
            assertEquals("new:AppCommand", copy.sources.get(0).display());
        }

        @Test
        void fieldCoverageSeparatesDeclaredAssignedInitialAndUnassigned() {
            AnalysisResult k = analyze("CaseAction", "bindingKinds");
            var cmd = k.objects.stream().filter(o -> o.className.equals("AppCommand")).findFirst().orElseThrow();
            assertEquals(List.of("appId", "shopId", "memo", "extra", "createdAt"), cmd.declaredFields);
            assertEquals(List.of("appId", "shopId", "memo"), cmd.assignedFields);
            assertEquals(List.of("createdAt"), cmd.initialOnlyFields);
            assertEquals(List.of("extra"), cmd.unassignedFields);
            assertEquals("VO", k.clazz(cmd.classId).role.label()); // *Command 도 VO 역할
            var copied = k.objects.stream().filter(o -> o.variable.equals("copy")).findFirst().orElseThrow();
            assertFalse(copied.unknownFields.isEmpty()); // copyProperties 대상은 필드별 매핑 확인 불가
        }

        @Test
        void inheritedFieldsAreExcludedFromCoverageByDefault() {
            AnalysisResult k = analyze("EdgeAction", "inheritedFields");
            var vo = k.objects.stream().filter(o -> o.className.equals("NoticeVO")).findFirst().orElseThrow();
            assertEquals(List.of("title", "body"), vo.declaredFields);       // 기본: BaseVO 필드(regId, regDate) 제외
            assertEquals(List.of("body"), vo.unassignedFields);
            assertEquals(List.of("title", "regId"), vo.assignedFields);    // 상속 필드 바인딩은 그대로
            assertEquals(false, k.settings.get("include-inheritance"));

            AnalysisResult all = analyze("EdgeAction", "inheritedFields", c -> c.analysis.includeInheritance = true);
            var vo2 = all.objects.stream().filter(o -> o.className.equals("NoticeVO")).findFirst().orElseThrow();
            assertEquals(List.of("regId", "regDate", "title", "body"), vo2.declaredFields);
            assertEquals(List.of("regDate", "body"), vo2.unassignedFields);
        }

        @Test
        void unusedSuperclassDoesNotAppearByDefault() {
            // 상위 클래스를 쓰지 않으면 BaseVO 는 어디에도 나오지 않는다 (extends 목록·SUPERTYPE 근거)
            AnalysisResult k = analyze("EdgeAction", "inheritedUnused");
            var notice = k.classes().stream().filter(c -> c.name.equals("NoticeVO")).findFirst().orElseThrow();
            assertEquals(List.of(), notice.extendsTypes);
            assertTrue(notice.roleEvidence.stream().noneMatch(e -> e.kind().equals("SUPERTYPE")), notice.roleEvidence.toString());
            assertEquals("VO", notice.role.label());
            assertTrue(k.classes().stream().noneMatch(c -> c.name.equals("BaseVO")));
            assertFalse(new analyzer.serializer.AnalysisYamlWriter().document(k).toString().contains("BaseVO"));

            AnalysisResult all = analyze("EdgeAction", "inheritedUnused", c -> c.analysis.includeInheritance = true);
            var n2 = all.classes().stream().filter(c -> c.name.equals("NoticeVO")).findFirst().orElseThrow();
            assertEquals(List.of("BaseVO"), n2.extendsTypes);
            assertTrue(n2.roleEvidence.stream().anyMatch(e -> e.kind().equals("SUPERTYPE")));
        }

        @Test
        void disabledBindings() {
            AnalysisResult off = analyze("CaseAction", "voBinding", c -> c.analysis.includeBindings = false);
            assertTrue(off.bindings.isEmpty());
            assertEquals(4, off.requestSpec.size());
        }
    }
}
