package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.serializer.AnalysisSchema;
import analyzer.serializer.AnalysisYamlWriter;
import analyzer.serializer.CallTreeWriter;
import analyzer.serializer.FlowView;
import analyzer.serializer.MappingReportWriter;
import analyzer.model.Expansion;
import analyzer.serializer.OutputWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputConsistencyTest {

    @SuppressWarnings("unchecked")
    @Test
    void yamlTreeAndMermaidShareIds(@TempDir Path tmp) throws Exception {
        AnalyzerConfig cfg = Fixtures.config("ScpAppListAction", "execute");
        cfg.output.directory = tmp.toString();
        AnalysisResult r = Main.analyze(cfg);
        Path dir = Main.outputDir(cfg, "ScpAppListAction");
        new OutputWriter().write(r, cfg, dir);

        for (String f : List.of("analysis.yaml", "call-tree.txt", "flowchart.mmd", "mapping.md", "analysis.md")) {
            assertTrue(Files.exists(dir.resolve(f)), f);
        }
        assertFalse(Files.exists(dir.resolve("comparison.md"))); // 신규 프로젝트 데이터가 없으면 만들지 않음
        Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(dir.resolve("analysis.yaml").toFile(), Map.class);
        assertEquals("1.0", doc.get("schemaVersion"));
        assertEquals(List.of(), AnalysisSchema.validate(doc));

        Set<String> methodIds = new HashSet<>();
        ((List<Map<String, Object>>) doc.get("methods")).forEach(m -> methodIds.add((String) m.get("id")));
        String mmd = Files.readString(dir.resolve("flowchart.mmd"));
        String tree = Files.readString(dir.resolve("call-tree.txt"));
        assertTrue(mmd.startsWith(MermaidGraph.INIT + "\nflowchart TD"));
        // 코드 줄 노드는 왼쪽 정렬 (themeCSS), 자동 줄바꿈 폭은 넉넉히
        assertTrue(MermaidGraph.INIT.contains(".codeline .nodeLabel") && MermaidGraph.INIT.contains("text-align: left"), MermaidGraph.INIT);
        assertTrue(MermaidGraph.INIT.contains("\"wrappingWidth\": 2400"));
        MermaidGraph graph = new FlowchartGenerator().build(r);
        for (Map<String, Object> c : (List<Map<String, Object>>) doc.get("calls")) {
            String id = (String) c.get("id");
            assertTrue(methodIds.contains((String) c.get("from")));
            if (FlowView.hidden(r, r.callSite(id))) {
                continue;
            }
            assertTrue(tree.contains("(" + id + ")"), "tree " + id);
            // catch/finally 안 호출은 흐름도에서 생략 (메서드 시작 노드의 ⚠ 요약으로 대체)
            // 연속된 요청 값 읽기는 첫 호출 ID 의 getParam 요약 노드 1개로 합쳐진다 (nodeOf)
            assertEquals(FlowchartGenerator.drawsCall(r, r.callSite(id)), mmd.contains("    " + graph.nodeOf(id) + "[\""), "Mermaid node " + id);
        }
        for (MethodInfo m : r.methods()) {
            if (m.analyzed && !FlowchartGenerator.skippedMethods(r).contains(m.id)) {
                assertTrue(mmd.contains("    " + m.id + "([\""), "Mermaid method node " + m.id);
            }
        }
        for (ControlBlock b : r.blocks) {
            assertTrue(tree.contains("(" + b.id + ")") || FlowView.hidden(r, b), "tree block " + b.id);
        }
        // 호출 엣지(==>)는 본문을 전개한 호출만, 모델의 대상과 일치
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        long callEdges = g.edges().stream().filter(e -> e.style() == MermaidGraph.EdgeStyle.CALL).count();
        // (상위 클래스 공통 메서드처럼 접은 메서드로는 ==> 없음 — 호출 노드 한 줄)
        var skipped = FlowchartGenerator.skippedMethods(r);
        assertEquals(r.callSites.stream().filter(s -> FlowchartGenerator.drawsCall(r, s) && (s.expansion == Expansion.EXPANDED
                || s.expansion == Expansion.SHARED || s.expansion == Expansion.CYCLE)
                && !skipped.contains(s.expansionTargetId())).count(), callEdges);
        // 서브그래프는 메서드당 1개 — 안에 중첩되는 건 try 블록뿐
        assertEquals(0, mmd.lines().filter(l -> l.trim().startsWith("subgraph") && l.startsWith("        ")
                && !l.trim().startsWith("subgraph sg_T")).count());
    }

    @ParameterizedTest
    @CsvSource({"CaseAction,overload", "CaseAction,duplicate", "CaseAction,recursive", "CaseAction,mutualA",
            "CaseAction,tryWithResources", "CaseAction,returnWithFinally", "CaseAction,finallyOverridesReturn",
            "CaseAction,throwCases", "CaseAction,switchAndLoop", "CaseAction,unresolvedCalls", "CaseAction,voBinding", "CaseAction,bindingKinds", "AppController,list"})
    void everyFixtureProducesValidMermaidAndTree(String clazz, String method) {
        AnalysisResult r = Fixtures.analyze(clazz, method);
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String tree = new CallTreeWriter(true).render(r);
        for (CallSiteInfo s : r.callSites) {
            // 바인딩 코드(setter 등)는 객체 요약 1줄로 접혀 개별 ID 를 표시하지 않음
            assertTrue(tree.contains("(" + s.id + ")") || FlowView.hidden(r, s), s.id);
        }
        for (ControlBlock b : r.blocks) {
            assertTrue(tree.contains("(" + b.id + ")") || FlowView.hidden(r, b), b.id);
        }
    }

    @Test
    void tryIsOneBlockWithBlockLevelExceptionEdges() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "throwCases");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        for (ControlBlock t : r.blocks) {
            if (t.type != analyzer.model.BlockType.TRY || FlowchartGenerator.inHandler(r, t.id)
                    || !g.has(t.methodId)) {
                continue;
            }
            // try = subgraph 1개, catch 마다 블록 → catch 예외 간선 1개
            assertTrue(mmd.contains("subgraph sg_" + t.id + "[\""), mmd);
            for (String cid : t.catchBlockIds) {
                assertTrue(g.edges().stream().anyMatch(e -> e.from().equals("sg_" + t.id) && e.to().equals(cid)
                        && e.style() == MermaidGraph.EdgeStyle.EXCEPTION), "block → " + cid);
            }
            if (t.finallyBlockId != null) {
                assertTrue(g.has(t.finallyBlockId));
            }
        }
        // 개별 throw 에는 catch 표시 없음, 메서드 시작 노드의 ⚠ 요약도 없음
        assertFalse(mmd.contains("→ catch "), mmd);
        assertFalse(mmd.contains("⚠ catch "), mmd);
    }

    @Test
    void returnAndThrowStayInsideTheMethodBox() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "throwCases");
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String mmd = new FlowchartGenerator().render(r, "TD");
        assertFalse(mmd.contains("이탈"), mmd);
        for (ControlBlock b : r.blocks) {
            if (!b.type.isLeaf() || !g.has(b.id)) {
                continue;
            }
            // return/throw 는 메서드 subgraph 안 (최상위 들여쓰기 4칸이 아님)
            assertFalse(mmd.contains("\n    " + b.id + "([") || mmd.contains("\n    " + b.id + ">\""), b.id);
            g.edges().stream().filter(e -> e.to().equals(b.id))
                    .forEach(e -> assertEquals(MermaidGraph.EdgeStyle.FLOW, e.style()));
        }
    }

    @Test
    void implementationLinkIsConsistentAcrossOutputs() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute", c -> {
            c.analysis.expandRoles = List.of("Service", "DAO");
            c.analysis.linkSingleImplementation = true;
        });
        CallSiteInfo site = r.callSites.stream().filter(s -> s.implementationMethodId != null).findFirst().orElseThrow();
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        assertTrue(g.edges().stream().anyMatch(e -> e.from().equals(site.id) && e.to().equals(site.implementationMethodId)));
        String tree = new CallTreeWriter(true).render(r);
        assertTrue(tree.contains("(" + site.id + ")  ⇒ 구현 후보 ScpAppListBizImpl.getAppList(AppListCommand)"), tree);
        assertTrue(tree.contains("ScpAppDAO.selectApps(String)"), tree);
        Map<String, Object> doc = new AnalysisYamlWriter().document(r);
        assertTrue(doc.get("calls").toString().contains("implementationMethodId=" + site.implementationMethodId));
    }

    @Test
    void treeWithoutBlocksMarksConditional() {
        AnalysisResult r = Fixtures.analyze("ScpAppListAction", "execute");
        String tree = new CallTreeWriter(false).render(r);
        assertFalse(tree.contains("(T1)"));
        assertTrue(tree.contains("logEmpty(String) [protected, D1] ?conditional"));
        assertTrue(tree.contains("↺") || !tree.contains("cycle"));
    }

    @Test
    void cycleShownInTree() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "recursive");
        assertTrue(new CallTreeWriter(true).render(r).contains("↺ cycle"));
    }

    @Test
    void mermaidEscapesSpecialCharacters() {
        assertEquals("a#35;b #quot;c#quot; #lt;T#gt; x#124;y", MermaidGraph.esc("a#b \"c\" <T> x|y"));
    }

    @Test
    void outputInsideSourceRootIsRejected() {
        AnalyzerConfig cfg = Fixtures.config("ScpAppListAction", "execute");
        cfg.output.directory = Fixtures.SOURCE_ROOT + "/out";
        assertThrows(IllegalArgumentException.class, () -> Main.outputDir(cfg, "X"));
    }

    @Test
    void sourceFilesAreNotModified() throws Exception {
        Path f = Path.of(Fixtures.SOURCE_ROOT, "com/example/action/ScpAppListAction.java");
        long before = Files.getLastModifiedTime(f).toMillis();
        String content = Files.readString(f);
        Fixtures.analyze("ScpAppListAction", "execute");
        assertEquals(before, Files.getLastModifiedTime(f).toMillis());
        assertEquals(content, Files.readString(f));
    }

    @Test
    void methodIdsAreStableAcrossRuns() {
        AnalysisResult a = Fixtures.analyze("ScpAppListAction", "execute");
        AnalysisResult b = Fixtures.analyze("ScpAppListAction", "execute");
        assertEquals(a.methods().stream().map(m -> m.id + "=" + m.key).toList(),
                b.methods().stream().map(m -> m.id + "=" + m.key).toList());
        assertTrue(a.methods().stream().map(m -> m.key).distinct().count() == a.methods().size());
    }

    @Test
    void bindingIsOneSummaryNodePerObjectAndMappingIsComplete() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "voBinding");
        String tree = new CallTreeWriter(true).render(r);
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        for (var b : r.bindings) {
            if (b.callSiteId != null) {
                assertFalse(g.has(b.callSiteId), "setter 는 개별 노드가 아님: " + b.callSiteId);
                assertFalse(tree.contains("(" + b.callSiteId + ")"));
            }
        }
        var summaries = FlowView.summaries(r);
        // voBinding 본문의 SearchVO 요약 1개 + 바인딩 메서드 fill() 호출 위치의 요약 1개
        assertEquals(2, summaries.size());
        summaries.forEach(sm -> assertFalse(g.has(sm.id()), "흐름도에는 바인딩 요약 노드 없음"));
        assertTrue(tree.contains("✎ SearchVO (vo) · 4 fields assigned"), tree);
        // 본문이 setter 뿐인 fill() 은 흐름도에 메서드로 그리지 않음
        var fill = Fixtures.method(r, "CaseAction", "fill(SearchVO, String)");
        assertFalse(g.has(fill.id));
        assertTrue(tree.contains("⇐ REQ page"), tree);       // 단독 Request 읽기는 흐름에 남음
        assertFalse(tree.contains("⇐ REQ keyword"), tree);   // setter 인자 안의 읽기는 바인딩으로 접힘
        // mapping.md 는 모든 바인딩을 생략 없이 포함
        String mapping = new MappingReportWriter().render(r);
        r.bindings.forEach(b -> assertTrue(mapping.contains("| " + b.id + " |"), b.id));
        String md = new analyzer.serializer.AnalysisReportWriter().render(r, tree, "flowchart LR\n", "SKIPPED");
        for (String section : List.of("## 1. 분석 개요", "## 2. 실행 흐름도", "## 3. Request Parameter 분석",
                "## 4. VO / DTO / Command 바인딩 명세", "## 5. 메서드 호출 관계", "## 6. 예외 처리")) {
            assertTrue(md.contains(section), section);
        }
    }

    @Test
    void multipleAssignmentsKeepOrderAndFinalValue() {
        AnalysisResult r = Fixtures.analyze("CaseAction", "bindingKinds");
        String mapping = new MappingReportWriter().render(r);
        assertTrue(mapping.contains("`\"1\".equals(pd)` → literal:testShop"), mapping);
        assertTrue(mapping.contains("그 외 → request.shopId"), mapping);
        assertTrue(mapping.contains("는 이후 무조건 할당"), mapping); // 생성자 할당은 setter 로 덮어써짐
        assertEquals(List.of(), AnalysisSchema.validate(yamlRoundTrip(r)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlRoundTrip(AnalysisResult r) {
        try {
            ObjectMapper m = new ObjectMapper(new YAMLFactory());
            return m.readValue(m.writeValueAsString(new AnalysisYamlWriter().document(r)), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void schemaRejectsBrokenDocument() {
        Map<String, Object> doc = yamlRoundTrip(Fixtures.analyze("CaseAction", "voBinding"));
        doc.put("schemaVersion", "2.0");
        ((Map<String, Object>) (Object) ((List<?>) doc.get("calls")).get(0)).put("from", "M999");
        List<String> errors = AnalysisSchema.validate(doc);
        assertTrue(errors.stream().anyMatch(e -> e.contains("schemaVersion")), errors.toString());
    }
}
