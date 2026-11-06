package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.serializer.CallTreeWriter;
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

        for (String f : List.of("classes.yaml", "call-relations.yaml", "control-blocks.yaml", "parameters.yaml",
                "call-tree.txt", "flowchart.mmd", "analysis.md")) {
            assertTrue(Files.exists(dir.resolve(f)), f);
        }
        ObjectMapper y = new ObjectMapper(new YAMLFactory());
        Map<String, Object> classes = y.readValue(dir.resolve("classes.yaml").toFile(), Map.class);
        Map<String, Object> calls = y.readValue(dir.resolve("call-relations.yaml").toFile(), Map.class);
        Map<String, Object> blocks = y.readValue(dir.resolve("control-blocks.yaml").toFile(), Map.class);

        Set<String> methodIds = new HashSet<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) classes.get("classes")) {
            for (Map<String, Object> m : (List<Map<String, Object>>) c.get("methods")) {
                methodIds.add((String) m.get("id"));
            }
        }
        Set<String> blockIds = new HashSet<>();
        for (Map<String, Object> b : (List<Map<String, Object>>) blocks.get("blocks")) {
            blockIds.add((String) b.get("id"));
        }
        String mmd = Files.readString(dir.resolve("flowchart.mmd"));
        String tree = Files.readString(dir.resolve("call-tree.txt"));
        assertTrue(mmd.startsWith("flowchart TD"));

        for (Map<String, Object> c : (List<Map<String, Object>>) calls.get("calls")) {
            String id = (String) c.get("id");
            assertTrue(methodIds.contains((String) c.get("from")));
            if (c.get("to") != null) {
                assertTrue(methodIds.contains((String) c.get("to")));
                assertTrue(mmd.contains(id + " ==>"), "Mermaid call edge for " + id);
            }
            if (c.get("control-block-id") != null) {
                assertTrue(blockIds.contains((String) c.get("control-block-id")));
            }
            assertTrue(mmd.contains("    " + id + "[") || mmd.contains(" " + id + "[\""), "Mermaid node " + id);
            assertTrue(tree.contains("(" + id + ")"), "tree " + id);
        }
        for (String m : methodIds) {
            assertTrue(mmd.matches("(?s).*\\b" + m + "[\\[(].*"), "Mermaid method node " + m);
        }
        for (String b : blockIds) {
            assertTrue(mmd.contains(b), "Mermaid block " + b);
            assertTrue(tree.contains("(" + b + ")"), "tree block " + b);
        }
        // Mermaid 의 모든 호출 엣지는 모델에 존재하는 호출 관계여야 함
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        long callEdges = g.edges().stream().filter(e -> e.style() == MermaidGraph.EdgeStyle.CALL).count();
        assertEquals(r.callSites.stream().filter(s -> s.targetMethodId != null).count(), callEdges);
    }

    @ParameterizedTest
    @CsvSource({"CaseAction,overload", "CaseAction,duplicate", "CaseAction,recursive", "CaseAction,mutualA",
            "CaseAction,tryWithResources", "CaseAction,returnWithFinally", "CaseAction,finallyOverridesReturn",
            "CaseAction,throwCases", "CaseAction,switchAndLoop", "CaseAction,unresolvedCalls", "AppController,list"})
    void everyFixtureProducesValidMermaidAndTree(String clazz, String method) {
        AnalysisResult r = Fixtures.analyze(clazz, method);
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        String tree = new CallTreeWriter(true).render(r);
        for (CallSiteInfo s : r.callSites) {
            assertTrue(tree.contains("(" + s.id + ")"), s.id);
        }
        for (ControlBlock b : r.blocks) {
            assertTrue(tree.contains("(" + b.id + ")"), b.id);
        }
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
}
