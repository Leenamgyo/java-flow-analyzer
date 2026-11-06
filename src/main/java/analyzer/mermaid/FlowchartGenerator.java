package analyzer.mermaid;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ClassRole;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.ResolutionStatus;
import analyzer.mermaid.MermaidGraph.EdgeStyle;
import analyzer.mermaid.MermaidGraph.Shape;
import analyzer.mermaid.MermaidGraph.Subgraph;
import analyzer.serializer.Labels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공통 분석 모델 → Mermaid flowchart. 소스를 다시 분석하지 않는다.
 * 노드 ID 는 YAML ID 를 그대로 사용하고, subgraph 는 "sg_" + ID 를 사용한다.
 * 실선(-->) = 정상 흐름, 굵은선(==>) = 메서드 호출, 점선(-.->) = 예외 흐름.
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
    }

    private AnalysisResult r;
    private MermaidGraph g;
    private final Map<String, Subgraph> methodGraphs = new HashMap<>();

    public MermaidGraph build(AnalysisResult result) {
        this.r = result;
        this.g = new MermaidGraph();
        methodGraphs.clear();
        for (ClassRole role : ClassRole.values()) {
            List<ClassInfo> classes = r.classes().stream().filter(c -> c.role == role).toList();
            if (classes.isEmpty()) {
                continue;
            }
            Subgraph rs = g.subgraph(g.root(), "role_" + role.name(), role.label());
            for (ClassInfo c : classes) {
                String title = c.name + " «" + role.label() + "»" + (c.origin.name().equals("SOURCE") ? "" : " (external)");
                Subgraph cs = g.subgraph(rs, "sg_" + c.id, title);
                for (String mid : c.methodIds) {
                    method(cs, r.method(mid));
                }
            }
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

    /** YAML ID ↔ Mermaid 노드 매핑 검증 */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph) {
        List<String> e = new ArrayList<>();
        r.methods().forEach(m -> {
            if (!graph.has(m.id)) {
                e.add("메서드 노드 누락: " + m.id);
            }
        });
        r.callSites.forEach(s -> {
            if (!graph.has(s.id)) {
                e.add("호출 노드 누락: " + s.id);
            }
        });
        r.blocks.forEach(b -> {
            if (!graph.has(b.id)) {
                e.add("블록 노드 누락: " + b.id);
            }
            if (!b.type.isLeaf() && !graph.has("sg_" + b.id)) {
                e.add("블록 subgraph 누락: sg_" + b.id);
            }
        });
        for (var ed : graph.edges()) {
            if (ed.style() == EdgeStyle.CALL) {
                CallSiteInfo s = r.callSite(ed.from());
                if (s == null || !ed.to().equals(s.targetMethodId)) {
                    e.add("모델에 없는 호출 엣지: " + ed.from() + " ==> " + ed.to());
                }
            }
        }
        return e;
    }

    private void method(Subgraph cs, MethodInfo m) {
        boolean entry = m.id.equals(r.entryMethodId);
        if (m.analyzed) {
            Subgraph ms = g.subgraph(cs, "sg_" + m.id, m.signature);
            methodGraphs.put(m.id, ms);
            g.node(ms, m.id, List.of(m.signature, m.visibility.text() + " · D" + m.depth), Shape.STADIUM,
                    entry ? "entry" : "method");
            container(ms, m, null, m.id, null);
        } else {
            String reason = m.notAnalyzedReason == null ? "" : m.notAnalyzedReason.name().toLowerCase().replace('_', '-');
            String css = m.notAnalyzedReason == Expansion.DEPTH_LIMIT ? "depth" : "external";
            g.node(cs, m.id, List.of(m.signature, m.visibility.text() + " · D" + m.depth + " · " + reason), Shape.RECT, css);
        }
    }

    /** 메서드/블록 본문 요소를 seq 순으로 연결하고 마지막 요소(다음 연결 시작점)를 반환 */
    private String container(Subgraph sg, MethodInfo m, ControlBlock parent, String prev, String firstLabel) {
        Set<String> header = parent == null ? Set.of() : headerPositions(parent.type);
        String label = firstLabel;
        for (Object o : Labels.elements(r, m.id, parent == null ? null : parent.id, false)) {
            if (o instanceof CallSiteInfo s) {
                if (header.contains(s.position)) {
                    continue;
                }
                callNode(sg, s);
                if (prev != null) {
                    g.edge(prev, s.id, label, EdgeStyle.FLOW);
                }
                label = null;
                prev = s.id;
            } else {
                ControlBlock b = (ControlBlock) o;
                if (b.type.isLeaf()) {
                    prev = container(sg, m, b, prev, label);
                    label = null;
                    leaf(sg, m, b, prev);
                    prev = null; // return/throw 이후 같은 블록의 정상 흐름은 없음
                } else {
                    prev = block(sg, m, b, prev, label);
                    label = null;
                }
            }
        }
        return prev;
    }

    private String block(Subgraph parentSg, MethodInfo m, ControlBlock b, String prev, String inLabel) {
        Subgraph sg = g.subgraph(parentSg, "sg_" + b.id, b.id + " · " + b.type.yamlName());
        String p = headerCalls(sg, b, prev, inLabel, EdgeStyle.FLOW);
        g.node(sg, b.id, headerLines(b), shape(b), null);
        g.edge(p, b.id, p == prev ? inLabel : null, EdgeStyle.FLOW);

        switch (b.type) {
            case IF -> {
                container(sg, m, b, b.id, "true");
                String from = b.id;
                for (ControlBlock child : branches(b)) {
                    branch(sg, m, child, from, "false", EdgeStyle.FLOW);
                    from = child.id;
                }
            }
            case SWITCH -> {
                for (ControlBlock child : branches(b)) {
                    branch(sg, m, child, b.id, null, EdgeStyle.FLOW);
                }
            }
            case TRY -> {
                container(sg, m, b, b.id, null);
                for (ControlBlock child : branches(b)) {
                    if (child.type == BlockType.CATCH) {
                        branch(sg, m, child, b.id, String.join(" | ", child.exceptionTypes), EdgeStyle.EXCEPTION);
                    } else {
                        branch(sg, m, child, b.id, "always", EdgeStyle.FLOW);
                    }
                }
            }
            case FOR, FOREACH, WHILE -> container(sg, m, b, b.id, "loop");
            default -> container(sg, m, b, b.id, null);
        }
        return sg.id();
    }

    private void branch(Subgraph parentSg, MethodInfo m, ControlBlock b, String from, String label, EdgeStyle style) {
        Subgraph sg = g.subgraph(parentSg, "sg_" + b.id, b.id + " · " + b.type.yamlName());
        String p = headerCalls(sg, b, from, label, style);
        g.node(sg, b.id, headerLines(b), shape(b), null);
        g.edge(p, b.id, p == from ? label : null, p == from ? style : EdgeStyle.FLOW);
        container(sg, m, b, b.id, b.type == BlockType.ELSE_IF ? "true" : null);
    }

    /** 조건식 등 헤더 위치 호출을 블록 헤더 앞에 연결 */
    private String headerCalls(Subgraph sg, ControlBlock b, String prev, String label, EdgeStyle style) {
        Set<String> header = headerPositions(b.type);
        String p = prev;
        boolean first = true;
        for (String cid : b.callIds) {
            CallSiteInfo s = r.callSite(cid);
            if (!header.contains(s.position)) {
                continue;
            }
            callNode(sg, s);
            g.edge(p, s.id, first ? label : null, first ? style : EdgeStyle.FLOW);
            first = false;
            p = s.id;
        }
        return p;
    }

    private void leaf(Subgraph sg, MethodInfo m, ControlBlock b, String prev) {
        List<String> lines = new ArrayList<>(List.of(Labels.blockHeader(b)));
        if (b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED) {
            lines.add("(catch 경로 unresolved)");
        }
        g.node(sg, b.id, lines, b.type == BlockType.RETURN ? Shape.STADIUM : Shape.ASYM,
                b.type == BlockType.RETURN ? "ret" : "throw");
        g.edge(prev, b.id, null, EdgeStyle.FLOW);
        EdgeStyle finStyle = b.type == BlockType.THROW ? EdgeStyle.EXCEPTION : EdgeStyle.FLOW;
        for (String f : b.executesFinally) {
            g.edge(b.id, f, "via finally", finStyle);
        }
        if (b.type == BlockType.THROW) {
            if (b.caughtBy != null) {
                g.edge(b.id, b.caughtBy, "caught", EdgeStyle.EXCEPTION);
            } else if (Boolean.TRUE.equals(b.propagatesToCaller)) {
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
        if (s.conditional && !s.conditions.isEmpty()) {
            info.append(" if: ").append(Labels.truncate(s.conditions.get(s.conditions.size() - 1), 50));
        } else if (s.conditional) {
            info.append(" conditional");
        }
        if (s.deferred) {
            info.append(" deferred");
        }
        lines.add(info.toString());
        String css = switch (s.expansion) {
            case NOT_APPLICABLE -> "unresolved";
            case CYCLE -> "cycle";
            default -> s.resolution == ResolutionStatus.RESOLVED ? null : "unresolved";
        };
        g.node(sg, s.id, lines, Shape.RECT, css);
        if (s.targetMethodId != null) {
            String label = switch (s.expansion) {
                case CYCLE -> "cycle";
                case DEPTH_LIMIT -> "depth-limit";
                case NO_BODY -> "no body";
                default -> null;
            };
            g.edge(s.id, s.targetMethodId, label, EdgeStyle.CALL);
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

    private static Shape shape(ControlBlock b) {
        return switch (b.type) {
            case IF, ELSE_IF, SWITCH -> Shape.DIAMOND;
            case ELSE, CASE -> Shape.ROUND;
            default -> Shape.HEX;
        };
    }

    private static Set<String> headerPositions(BlockType t) {
        return switch (t) {
            case IF, ELSE_IF, WHILE -> Set.of("CONDITION");
            case FOR -> Set.of("INITIALIZER", "CONDITION");
            case FOREACH -> Set.of("ITERABLE");
            case SWITCH -> Set.of("SELECTOR");
            default -> Set.of();
        };
    }
}
