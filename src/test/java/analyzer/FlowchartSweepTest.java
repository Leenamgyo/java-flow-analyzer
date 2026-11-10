package analyzer;

import analyzer.config.AnalyzerConfig;
import analyzer.mermaid.FlowchartGenerator;
import analyzer.mermaid.MermaidGraph;
import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.VariableChange;
import analyzer.phase.PhaseAnalyzer;
import analyzer.serializer.Labels;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 모든 fixture 진입 메서드 × 설정 조합으로 흐름도(일반·분할·단계 보기)를 그려 본다.
 * 생성기 자체 검증(checkMapping)과 별개로, 렌더링된 텍스트에서 코드가 실제로 보이는지 독립적으로 확인한다(텍스트 오라클).
 * FLOW_DUMP 환경 변수가 있으면 그 디렉터리에 .mmd 를 떨궈 Mermaid 파서로 따로 검사할 수 있다.
 */
class FlowchartSweepTest {

    static final List<String[]> ENTRIES = List.of(
            new String[]{"ScpAppListAction", "execute"}, new String[]{"GetVasAppList", "execute"},
            new String[]{"AppController", "list"},
            new String[]{"CaseAction", "overload"}, new String[]{"CaseAction", "duplicate"}, new String[]{"CaseAction", "recursive"},
            new String[]{"CaseAction", "mutualA"}, new String[]{"CaseAction", "tryWithResources"},
            new String[]{"CaseAction", "returnWithFinally"}, new String[]{"CaseAction", "finallyOverridesReturn"},
            new String[]{"CaseAction", "throwCases"}, new String[]{"CaseAction", "switchAndLoop"},
            new String[]{"CaseAction", "multipleImplementations"}, new String[]{"CaseAction", "unresolvedCalls"},
            new String[]{"CaseAction", "voBinding"}, new String[]{"CaseAction", "bindingKinds"},
            new String[]{"CaseAction", "variableState"}, new String[]{"CaseAction", "stateWithLogic"},
            new String[]{"CaseAction", "stateInTry"}, new String[]{"CaseAction", "statementBinding"},
            new String[]{"CaseAction", "tryOnlyExternal"}, new String[]{"CaseAction", "flowRules"},
            new String[]{"EdgeAction", "nestedLoops"}, new String[]{"EdgeAction", "loopExits"},
            new String[]{"EdgeAction", "bindingChain"}, new String[]{"EdgeAction", "stateTargets"},
            new String[]{"EdgeAction", "logVariants"}, new String[]{"EdgeAction", "tryInLoop"},
            new String[]{"EdgeAction", "multiReadStatement"}, new String[]{"EdgeAction", "emptyLoops"},
            new String[]{"EdgeAction", "whileWithCall"}, new String[]{"EdgeAction", "bindingChain"},
            new String[]{"EdgeAction", "holderVariants"}, new String[]{"EdgeAction", "inlineRepeats"},
            new String[]{"EdgeAction", "inlineNested"}, new String[]{"EdgeAction", "stateWithCalls"});

    static final Map<String, Consumer<AnalyzerConfig>> VARIANTS = Map.of(
            "default", c -> {
            },
            "external", c -> c.analysis.includeExternalLibraryCalls = true,
            "deep", c -> {
                c.analysis.maxDepth = 4;
                c.analysis.linkSingleImplementation = true;
            },
            "flat", c -> {
                c.analysis.expandSameClassMethods = false;
                c.analysis.expandRoles = List.of();
            });

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        for (String[] e : ENTRIES) {
            for (String v : VARIANTS.keySet().stream().sorted().toList()) {
                out.add(Arguments.of(e[0], e[1], v));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}.{1} [{2}]")
    @MethodSource("cases")
    void everyViewRendersAndNoCodeIsMissing(String clazz, String method, String variant) throws Exception {
        AnalysisResult r = Fixtures.analyze(clazz, method, VARIANTS.get(variant));
        FlowchartGenerator gen = new FlowchartGenerator();
        String mmd = gen.render(r, "TD"); // 내부 검증(validate + checkMapping) 포함
        MermaidGraph g = new FlowchartGenerator().build(r);
        assertEquals(List.of(), g.validate());
        assertEquals(List.of(), FlowchartGenerator.checkMapping(r, g));
        assertEquals(List.of(), repeatedExpansions(g));
        FlowchartGenerator.Sectioned sec = new FlowchartGenerator().renderSectioned(r, "TD", "dagre", 20);
        List<FlowchartGenerator.Part> parts = new java.util.ArrayList<>(sec.skeletons());
        parts.addAll(sec.sections());
        String phase = new FlowchartGenerator(PhaseAnalyzer.analyze(r, new AnalyzerConfig.Phases())).render(r, "TD");
        dump(variant, clazz + "." + method, mmd, parts, phase);

        List<String> missing = missingCode(r, mmd);
        assertEquals(List.of(), missing, mmd);
        // 빈 Loop·try 상자가 생겨도 subgraph 는 렌더링 가능해야 함 (빈 subgraph 는 Mermaid 오류 — 노드가 하나는 있어야)
        for (String line : mmd.lines().toList()) {
            assertFalse(line.trim().matches("subgraph \\S+\\[\"\"\\]"), line);
        }
        assertNoEmptySubgraph(mmd);
    }

    @ParameterizedTest(name = "{0}.{1} [exclude]")
    @MethodSource("excludeCases")
    void excludedCodeIsGoneAndTheRestStillComplete(String clazz, String method) throws Exception {
        AnalysisResult r = Fixtures.analyze(clazz, method);
        List<String> patterns = List.of("LOG.*", "*.release", "CaseAction.compute", "EdgeAction.touch", "*Util.*");
        String mmd = new FlowchartGenerator().exclude(patterns).render(r, "TD");
        List<FlowchartGenerator.Part> parts = new FlowchartGenerator().exclude(patterns).renderSectioned(r, "TD", "dagre", 20).sections();
        dump("exclude", clazz + "." + method, mmd, parts, null);
        String flat = normalize(mmd);
        for (CallSiteInfo s : r.callSites) {
            boolean ex = s.methodName.equals("release") || s.methodName.equals("touch")
                    || s.scopeExpression != null && (s.scopeExpression.equals("LOG") || s.scopeExpression.endsWith("Util"))
                    || s.methodName.equals("compute") && s.scopeExpression == null;
            if (ex && s.statement == null && !s.inArgument && s.controlBlockId == null) {
                // 최상위 단독 문장인 제외 호출은 흐름도 어디에도 없어야 한다
                assertFalse(flat.contains(strip(s.expression) + "·") || flat.contains("#" + s.order + strip(s.expression)),
                        "excluded but drawn: " + s.expression + "\n" + mmd);
            }
        }
        assertTrue(mmd.contains("%% flowchart-exclude"), mmd);
    }

    static Stream<Arguments> excludeCases() {
        return ENTRIES.stream().map(e -> Arguments.of(e[0], e[1]));
    }

    // ------------------------------------------------------------------ 텍스트 오라클

    /** 렌더링된 흐름도 텍스트를 사람이 보는 글자로 되돌리고 공백을 모두 뺀다 (줄바꿈 위치와 무관하게 비교) */
    /**
     * 노드 하나 안에서 같은 바인딩 함수 본문을 두 번 펼치지 않는다 (사용자 보고: 함수가 내재된 경우 같은 선언이 여러 번) —
     * 같은 ▸ 호출 줄이 다시 나오면 바로 다음 줄은 "같은 본문"/"cycle" 이어야 하고, 바인딩 줄로 보이는 setter 호출을
     * ▸ 로 또 펼치지 않는다.
     */
    static List<String> repeatedExpansions(MermaidGraph g) {
        List<String> out = new ArrayList<>();
        for (String id : g.nodeIds()) {
            List<String> lines = g.nodeById(id).lines().stream().map(MermaidGraph::plain).toList();
            Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).replaceFirst("▸ #\\d+ ", "▸ ");
                if (!l.trim().startsWith("│") && !l.startsWith("▸")) {
                    continue;
                }
                if (l.matches(".*▸ \\w+\\.set[A-Z]\\w*\\(.*")) {
                    out.add(id + ": setter 를 ▸ 로 펼침 — " + l);
                }
                int at = l.indexOf("▸ ");
                if (at < 0) {
                    continue;
                }
                String call = l.substring(at).replaceFirst("^▸ (?:[\\w<>\\[\\], ]+ )?\\w+ = ", "▸ ");
                if (!seen.add(call)) {
                    String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
                    if (!next.contains("같은 본문") && !next.contains("cycle")) {
                        out.add(id + ": 같은 함수 본문을 다시 펼침 — " + l);
                    }
                }
            }
        }
        return out;
    }

    static String normalize(String mmd) {
        String s = mmd.replaceAll("<font color='[^']*'>", "").replace("</font>", "")
                .replace("<br/>#gt; ", "").replace("<br/>", "\n") // 이어지는 줄 표시 "> " 는 이스케이프되어 있다
                .replace("#quot;", "\"").replace("#lt;", "<").replace("#gt;", ">").replace("#124;", "|")
                .replace("#96;", "`").replace("#35;", "#");
        return strip(s);
    }

    static String strip(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    /**
     * 그려야 하는 메서드(진입에서 상자로 그린 것 + 바인딩 블록에 중첩된 바인딩 함수)의 모든 호출·블록·바인딩·변수 변경이
     * 흐름도 텍스트 어딘가에 보이는지. 호출은 자기 식, 감싸는 문장·호출·조건·return/throw·바인딩 값·try 본문 중 하나로 보이면 된다.
     */
    static List<String> missingCode(AnalysisResult r, String mmd) {
        String flat = normalize(mmd);
        Set<String> shown = new LinkedHashSet<>();
        for (MethodInfo m : r.methods()) {
            if (m.analyzed && (flat.contains(m.id + "([\"") || mmd.contains("    " + m.id + "([\""))) {
                shown.add(m.id);
            }
        }
        shown.addAll(FlowchartGenerator.bindingFunctions(r));
        List<String> missing = new ArrayList<>();
        for (CallSiteInfo s : r.callSites) {
            if (!shown.contains(s.callerMethodId)) {
                continue;
            }
            if (candidates(r, s).stream().map(FlowchartSweepTest::strip).noneMatch(c -> !c.isEmpty() && flat.contains(c))) {
                missing.add("call " + s.id + " `" + s.expression + "` in " + r.method(s.callerMethodId).name);
            }
        }
        for (ControlBlock b : r.blocks) {
            if (!shown.contains(b.methodId) || b.type == BlockType.TRY || b.type == BlockType.ELSE) {
                continue;
            }
            String text = switch (b.type) {
                case CASE -> b.caseLabels.isEmpty() ? "default" : String.join(",", b.caseLabels);
                case CATCH -> "catch" + String.join("|", b.exceptionTypes);
                case FINALLY -> "finally";
                case RETURN, THROW -> b.expression == null ? b.type.yamlName()
                        : Labels.codeValue(r, b.methodId, b.location, b.expression);
                default -> Labels.codeValue(r, b.methodId, b.location, b.condition);
            };
            if (!flat.contains(strip(text)) && !flat.contains(strip(b.condition == null ? "?" : b.condition))) {
                missing.add("block " + b.id + " " + b.type + " `" + text + "`");
            }
        }
        for (Binding b : r.bindings) {
            if (!shown.contains(b.methodId)) {
                continue;
            }
            String line = Labels.bindingLine(r, b, false);
            if (!flat.contains(strip(line))) {
                missing.add("binding " + b.id + " `" + line + "` in " + r.method(b.methodId).name);
            }
        }
        for (VariableChange c : r.variableChanges) {
            if (!c.isChange() || !shown.contains(c.methodId)) {
                continue;
            }
            if (!flat.contains(strip(Labels.codeValue(r, c.methodId, c.location, c.expression))) && !flat.contains(strip(c.expression))
                    && !(c.valueExpression != null && flat.contains(strip(c.valueExpression)))) {
                missing.add("change " + c.id + " `" + c.expression + "`");
            }
        }
        return missing;
    }

    static List<String> candidates(AnalysisResult r, CallSiteInfo s) {
        List<String> out = new ArrayList<>();
        String mid = s.callerMethodId;
        out.add(s.expression);
        out.add(Labels.codeValue(r, mid, s.location, s.expression));
        out.add(Labels.logLine(s).replaceFirst("^#\\d+ ", "")); // 로그 블록 줄 (수신 객체 생략은 규칙)
        r.parametersAt(s.id).forEach(p -> out.add(Labels.requestValue(p)));
        if (s.statement != null) {
            out.add(stripSemi(s.statement));
            out.add(stripSemi(Labels.codeValue(r, mid, s.statementLocation, s.statement)));
        }
        for (CallSiteInfo o : r.callSitesOf(mid)) {
            if (o != s && contains(o.location, s.location)) {
                out.add(o.expression);
                out.add(Labels.codeValue(r, mid, o.location, o.expression));
                out.add(Labels.logLine(o).replaceFirst("^#\\d+ ", ""));
                if (o.statement != null) {
                    out.add(stripSemi(Labels.codeValue(r, mid, o.statementLocation, o.statement)));
                }
            }
        }
        for (ControlBlock b : r.blocksOf(mid)) {
            if (b.id.equals(s.controlBlockId) || contains(b.location, s.location)) {
                if (b.condition != null && s.id != null && List.of("CONDITION", "SELECTOR", "ITERABLE", "INITIALIZER", "UPDATE")
                        .contains(s.position)) {
                    out.add(Labels.codeValue(r, mid, b.location, b.condition));
                }
                if ((b.type == BlockType.RETURN || b.type == BlockType.THROW) && b.expression != null) {
                    out.add(Labels.codeValue(r, mid, b.location, b.expression));
                }
                if (b.type == BlockType.TRY && b.bodyText != null) {
                    out.add(b.bodyText);
                }
            }
        }
        for (Binding b : r.bindings) {
            if (b.methodId.equals(mid) && contains(b.location, s.location) && b.valueExpression != null) {
                out.add(Labels.codeValue(r, mid, b.location, b.valueExpression));
            }
        }
        return out;
    }

    static String stripSemi(String s) {
        s = s.trim();
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    static boolean contains(analyzer.model.SourceLocation outer, analyzer.model.SourceLocation inner) {
        if (outer == null || inner == null || !java.util.Objects.equals(outer.file(), inner.file())) {
            return false;
        }
        boolean begin = outer.beginLine() < inner.beginLine()
                || outer.beginLine() == inner.beginLine() && outer.beginColumn() <= inner.beginColumn();
        boolean end = inner.endLine() < outer.endLine()
                || inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn();
        return begin && end;
    }

    /** subgraph … end 사이에 노드가 하나도 없으면 안 된다 */
    static void assertNoEmptySubgraph(String mmd) {
        List<String> lines = mmd.lines().toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).trim().startsWith("subgraph ")) {
                assertFalse(lines.get(i + 1).trim().equals("end"), "empty subgraph: " + lines.get(i) + "\n" + mmd);
            }
        }
    }

    static void dump(String variant, String name, String mmd, List<FlowchartGenerator.Part> parts, String phase) throws Exception {
        String dir = System.getenv("FLOW_DUMP");
        if (dir == null) {
            return;
        }
        Path d = Path.of(dir, variant);
        Files.createDirectories(d);
        Files.writeString(d.resolve(name + ".mmd"), mmd);
        if (phase != null) {
            Files.writeString(d.resolve(name + ".phase.mmd"), phase);
        }
        for (FlowchartGenerator.Part p : parts) {
            Files.writeString(d.resolve(name + ".part." + p.file()), p.mermaid());
        }
    }
}
