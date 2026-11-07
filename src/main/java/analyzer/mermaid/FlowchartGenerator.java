package analyzer.mermaid;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import analyzer.mermaid.MermaidGraph.EdgeStyle;
import analyzer.mermaid.MermaidGraph.Shape;
import analyzer.mermaid.MermaidGraph.Subgraph;
import analyzer.serializer.FlowView;
import analyzer.serializer.Labels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공통 분석 모델 → Mermaid flowchart (소스를 다시 분석하지 않는다).
 * <ul>
 *   <li>subgraph 는 분석한 메서드당 1개뿐 (중첩 최소화). 노드 ID = YAML ID</li>
 *   <li>if/else-if/switch/반복문 = 조건 노드 + 분기 간선(Yes/No, case)</li>
 *   <li>try/catch/finally 는 구현 세부라 노드로 그리지 않는다: try 본문은 정상 흐름에 그대로 이어지고,
 *       catch/finally 본문(과 그 안에서만 호출되는 메서드)은 생략, 메서드 시작 노드에 한 줄 요약(⚠ catch …)</li>
 *   <li>실선(-->) 정상 흐름, 굵은선(==>) 본문을 전개한 메서드 호출, 점선(-.->) 처리되지 않은 throw</li>
 *   <li>객체 바인딩(set/builder/put)은 그리지 않는다 — 상세는 mapping.md / analysis.yaml / call-tree</li>
 * </ul>
 */
public final class FlowchartGenerator {

    public static final Map<String, String> CLASS_DEFS = new LinkedHashMap<>();

    static {
        CLASS_DEFS.put("entry", "fill:#dbeafe,stroke:#1d4ed8,stroke-width:2px");
        CLASS_DEFS.put("method", "fill:#eef2ff,stroke:#4338ca");
        CLASS_DEFS.put("external", "fill:#f3f4f6,stroke:#9ca3af,color:#374151");
        CLASS_DEFS.put("depth", "fill:#f5f5f4,stroke:#78716c,stroke-dasharray:2 2");
        CLASS_DEFS.put("unresolved", "fill:#fff7ed,stroke:#c2410c,stroke-dasharray:4 3");
        CLASS_DEFS.put("cycle", "fill:#fef9c3,stroke:#a16207");
        CLASS_DEFS.put("ret", "fill:#dcfce7,stroke:#15803d");
        CLASS_DEFS.put("throw", "fill:#fee2e2,stroke:#b91c1c");
        CLASS_DEFS.put("exc", "fill:#fee2e2,stroke:#b91c1c,stroke-dasharray:4 3");
        CLASS_DEFS.put("req", "fill:#ccfbf1,stroke:#0f766e,stroke-width:2px");
    }

    /** 다음 요소로 이어질 열린 끝 (노드 + 간선 라벨/스타일) */
    private record End(String node, String label, EdgeStyle style) {
        End(String node) {
            this(node, null, EdgeStyle.FLOW);
        }
    }

    private AnalysisResult r;
    private MermaidGraph g;
    private final Map<String, Subgraph> methodGraphs = new HashMap<>();

    public MermaidGraph build(AnalysisResult result) {
        this.r = result;
        this.g = new MermaidGraph();
        methodGraphs.clear();
        Set<String> skip = skippedMethods(r);
        for (MethodInfo m : r.methods()) {
            if (!m.analyzed || skip.contains(m.id)) {
                continue;
            }
            String cls = r.classOf(m).name;
            Subgraph ms = g.subgraph(g.root(), "sg_" + m.id, cls + "." + m.name + "() «" + r.classOf(m).role.label() + "»");
            methodGraphs.put(m.id, ms);
            List<String> lines = new ArrayList<>(List.of(cls + "." + m.signature, m.visibility.text() + " · D" + m.depth));
            String req = Labels.methodRequestTag(r, m);
            if (req != null) {
                lines.add(Labels.truncate(req, 120));
            }
            lines.addAll(exceptionNotes(r, m));
            g.node(ms, m.id, lines, Shape.STADIUM, m.id.equals(r.entryMethodId) ? "entry" : "method");
            emit(ms, m, null, List.of(new End(m.id)));
        }
        return g;
    }

    public String render(AnalysisResult result, String direction) {
        MermaidGraph graph = build(result);
        List<String> errors = new ArrayList<>(graph.validate());
        errors.addAll(checkMapping(result, graph));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        return graph.render(direction, CLASS_DEFS);
    }

    /** YAML ID ↔ Mermaid 노드 매핑 검증 (흐름에서 뺀 바인딩 코드는 제외, else/case 는 간선으로 표현) */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph) {
        List<String> e = new ArrayList<>();
        Set<String> skip = skippedMethods(r);
        r.methods().stream().filter(m -> m.analyzed && !skip.contains(m.id)).forEach(m -> {
            if (!graph.has(m.id)) {
                e.add("메서드 노드 누락: " + m.id);
            }
        });
        r.callSites.stream().filter(s -> drawn(r, skip, s)).forEach(s -> {
            if (!graph.has(s.id)) {
                e.add("호출 노드 누락: " + s.id);
            }
        });
        r.blocks.stream().filter(b -> !FlowView.hidden(r, b) && !skip.contains(b.methodId) && !inHandler(r, b.id)
                        && b.type != BlockType.ELSE && b.type != BlockType.CASE && b.type != BlockType.TRY)
                .forEach(b -> {
                    if (!graph.has(b.id)) {
                        e.add("블록 노드 누락: " + b.id);
                    }
                });
        for (var ed : graph.edges()) {
            if (ed.style() == EdgeStyle.CALL) {
                CallSiteInfo s = r.callSite(ed.from());
                if (s == null || !(ed.to().equals(s.targetMethodId) || ed.to().equals(s.implementationMethodId))) {
                    e.add("모델에 없는 호출 엣지: " + ed.from() + " ==> " + ed.to());
                }
            }
        }
        return e;
    }

    // ------------------------------------------------------------------ try/catch 생략 규칙

    /** 블록이 catch/finally 본문 안(자기 자신 포함)인지 */
    static boolean inHandler(AnalysisResult r, String blockId) {
        for (String cur = blockId; cur != null; ) {
            ControlBlock b = r.block(cur);
            if (b.type == BlockType.CATCH || b.type == BlockType.FINALLY) {
                return true;
            }
            cur = b.parentBlockId;
        }
        return false;
    }

    /** 흐름도에 그리는 호출: 바인딩 코드가 아니고, catch/finally 안이 아니며, 그리는 메서드 안 */
    private static boolean drawn(AnalysisResult r, Set<String> skip, CallSiteInfo s) {
        return !FlowView.hidden(r, s) && !skip.contains(s.callerMethodId)
                && (s.controlBlockId == null || !inHandler(r, s.controlBlockId));
    }

    /** 흐름도에 노드로 그리는 호출인지 (테스트·검증용) */
    public static boolean drawsCall(AnalysisResult r, CallSiteInfo s) {
        return drawn(r, skippedMethods(r), s);
    }

    /** 그리지 않는 메서드: 바인딩 메서드 + catch/finally 안에서만 호출되는 메서드(전이적으로) */
    public static Set<String> skippedMethods(AnalysisResult r) {
        Set<String> skip = new java.util.HashSet<>(FlowView.bindingMethods(r));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo m : r.methods()) {
                if (!m.analyzed || skip.contains(m.id) || m.id.equals(r.entryMethodId)) {
                    continue;
                }
                List<CallSiteInfo> in = r.callSites.stream()
                        .filter(s -> s.targetMethodId != null && m.id.equals(s.expansionTargetId())).toList();
                if (!in.isEmpty() && in.stream().noneMatch(s -> drawn(r, skip, s))) {
                    skip.add(m.id);
                    changed = true;
                }
            }
        }
        return skip;
    }

    /** 메서드 시작 노드에 붙는 예외 처리 요약: ⚠ catch SQLException → log · error response / finally */
    private static List<String> exceptionNotes(AnalysisResult r, MethodInfo m) {
        List<String> out = new ArrayList<>();
        for (ControlBlock t : r.blocksOf(m.id)) {
            if (t.type != BlockType.TRY || FlowView.hidden(r, t) || inHandler(r, t.id)) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (String cid : t.catchBlockIds) {
                ControlBlock c = r.block(cid);
                List<String> how = new ArrayList<>();
                if (!c.logCalls.isEmpty()) {
                    how.add("log");
                }
                if (!c.errorResponseCalls.isEmpty()) {
                    how.add("error response");
                }
                if (c.rethrows) {
                    how.add("rethrow");
                } else if (c.throwsNewException) {
                    how.add("throw new");
                }
                if (c.containsReturn) {
                    how.add("return");
                }
                parts.add("catch " + String.join(" | ", c.exceptionTypes) + (how.isEmpty() ? "" : " → " + String.join(" · ", how)));
            }
            if (t.finallyBlockId != null) {
                parts.add("finally");
            }
            out.add(Labels.truncate("⚠ " + String.join(" / ", parts), 120));
        }
        return out;
    }

    // ------------------------------------------------------------------ flow

    private List<End> emit(Subgraph sg, MethodInfo m, ControlBlock parent, List<End> in) {
        Set<String> header = parent == null ? Set.of() : headerPositions(parent.type);
        List<End> cur = in;
        for (Object o : FlowView.elements(r, m.id, parent == null ? null : parent.id, false)) {
            if (o instanceof FlowView.Summary) {
                continue; // 바인딩(set/builder/put)은 흐름도에 그리지 않음 — mapping.md / call-tree 에서 확인
            } else if (o instanceof CallSiteInfo s) {
                if (header.contains(s.position)) {
                    continue;
                }
                callNode(sg, s);
                cur = link(cur, s.id);
            } else {
                cur = block(sg, m, (ControlBlock) o, cur);
            }
        }
        return cur;
    }

    private List<End> block(Subgraph sg, MethodInfo m, ControlBlock b, List<End> in) {
        switch (b.type) {
            case IF -> {
                decision(sg, b, in);
                List<End> out = new ArrayList<>(emit(sg, m, b, List.of(new End(b.id, "Yes", EdgeStyle.FLOW))));
                End no = new End(b.id, "No", EdgeStyle.FLOW);
                for (ControlBlock c : branches(b)) {
                    if (c.type == BlockType.ELSE_IF) {
                        decision(sg, c, List.of(no));
                        out.addAll(emit(sg, m, c, List.of(new End(c.id, "Yes", EdgeStyle.FLOW))));
                        no = new End(c.id, "No", EdgeStyle.FLOW);
                    } else {
                        out.addAll(emit(sg, m, c, List.of(no)));
                        no = null;
                    }
                }
                if (no != null) {
                    out.add(no);
                }
                return out;
            }
            case SWITCH -> {
                decision(sg, b, in);
                List<End> out = new ArrayList<>();
                boolean hasDefault = false;
                for (ControlBlock c : branches(b)) {
                    hasDefault |= c.isDefault || c.caseLabels.isEmpty();
                    out.addAll(emit(sg, m, c, List.of(new End(b.id, Labels.truncate(Labels.blockHeader(c), 40), EdgeStyle.FLOW))));
                }
                if (!hasDefault) {
                    out.add(new End(b.id, "no match", EdgeStyle.FLOW));
                }
                return out;
            }
            case FOR, FOREACH, WHILE, DO_WHILE -> {
                decision(sg, b, in);
                for (End e : emit(sg, m, b, List.of(new End(b.id, "loop", EdgeStyle.FLOW)))) {
                    g.edge(e.node(), b.id, e.label() == null ? "next" : e.label(), e.style());
                }
                return List.of(new End(b.id, "done", EdgeStyle.FLOW));
            }
            case TRY -> {
                return emit(sg, m, b, in); // try 본문만 정상 흐름으로, catch/finally 는 시작 노드 요약
            }
            case CATCH, FINALLY -> {
                return in;
            }
            case RETURN, THROW -> {
                List<End> p = emit(sg, m, b, in);
                leaf(sg, m, b);
                link(p, b.id);
                return List.of();
            }
            default -> {
                return emit(sg, m, b, in);
            }
        }
    }

    /** 헤더 위치 호출(조건식 안 호출 등) → 조건 노드 */
    private void decision(Subgraph sg, ControlBlock b, List<End> in) {
        List<End> cur = in;
        for (String cid : b.callIds) {
            CallSiteInfo s = r.callSite(cid);
            if (headerPositions(b.type).contains(s.position) && !FlowView.hidden(r, s)) {
                callNode(sg, s);
                cur = link(cur, s.id);
            }
        }
        g.node(sg, b.id, headerLines(b), Shape.DIAMOND, null);
        link(cur, b.id);
    }

    private List<End> link(List<End> from, String to) {
        for (End e : from) {
            g.edge(e.node(), to, e.label(), e.style());
        }
        return List.of(new End(to));
    }

    private void leaf(Subgraph sg, MethodInfo m, ControlBlock b) {
        List<String> lines = new ArrayList<>(List.of(Labels.blockHeader(b)));
        if (b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED) {
            lines.add("(catch 경로 unresolved)");
        }
        if (b.type == BlockType.THROW && b.caughtBy != null) {
            lines.add("→ " + Labels.blockHeader(r.block(b.caughtBy)));
        }
        g.node(sg, b.id, lines, b.type == BlockType.RETURN ? Shape.STADIUM : Shape.ASYM,
                b.type == BlockType.RETURN ? "ret" : "throw");
        if (b.type == BlockType.THROW && b.caughtBy == null) {
            if (Boolean.TRUE.equals(b.propagatesToCaller)) {
                String exit = m.id + "_uncaught";
                if (!g.has(exit)) {
                    g.node(methodGraphs.get(m.id), exit, List.of("uncaught → caller"), Shape.SUBROUTINE, "exc");
                }
                g.edge(b.id, exit, "uncaught", EdgeStyle.EXCEPTION);
            }
        }
    }

    private void callNode(Subgraph sg, CallSiteInfo s) {
        List<String> lines = new ArrayList<>();
        lines.add("#" + s.order + " " + Labels.callTarget(r, s));
        StringBuilder info = new StringBuilder(Labels.callTags(r, s));
        switch (s.expansion) {
            case DEPTH_LIMIT -> info.append(" depth-limit");
            case NO_BODY -> info.append(" no body");
            case CYCLE -> info.append(" cycle");
            default -> {
            }
        }
        if (s.deferred) {
            info.append(" deferred");
        }
        lines.add(info.toString());
        String impl = Labels.implementationTarget(r, s);
        if (impl != null) {
            lines.add("impl 후보: " + Labels.truncate(impl, 60));
        }
        String req = Labels.requestTag(r, s);
        if (req != null) {
            lines.add(req);
        }
        String css = switch (s.expansion) {
            case NOT_APPLICABLE -> "unresolved";
            case CYCLE -> "cycle";
            case DEPTH_LIMIT -> req != null ? "req" : "depth";
            case EXTERNAL, NO_BODY -> req != null ? "req" : s.resolution == ResolutionStatus.RESOLVED ? "external" : "unresolved";
            default -> req != null ? "req" : s.resolution == ResolutionStatus.RESOLVED ? null : "unresolved";
        };
        g.node(sg, s.id, lines, Shape.RECT, css);
        if (s.targetMethodId != null && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED
                || s.expansion == Expansion.CYCLE)) {
            String label = switch (s.expansion) {
                case SHARED -> "shared";
                case CYCLE -> "cycle";
                default -> s.implementationMethodId != null ? "impl 후보" : null;
            };
            g.edge(s.id, s.expansionTargetId(), label, EdgeStyle.CALL);
        }
    }

    private List<ControlBlock> branches(ControlBlock b) {
        return b.childBlockIds.stream().map(r::block).filter(c -> "BRANCH".equals(c.relation)).toList();
    }

    private static List<String> headerLines(ControlBlock b) {
        List<String> l = new ArrayList<>(List.of(Labels.blockHeader(b)));
        String notes = Labels.blockNotes(b).trim();
        if (!notes.isEmpty()) {
            l.add(notes.replace("⟨", "").replace("⟩", ""));
        }
        return l;
    }

    private static Set<String> headerPositions(BlockType t) {
        return switch (t) {
            case IF, ELSE_IF, WHILE, DO_WHILE -> Set.of("CONDITION");
            case FOR -> Set.of("INITIALIZER", "CONDITION");
            case FOREACH -> Set.of("ITERABLE");
            case SWITCH -> Set.of("SELECTOR");
            default -> Set.of();
        };
    }
}
