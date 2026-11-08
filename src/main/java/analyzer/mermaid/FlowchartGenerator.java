package analyzer.mermaid;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.SourceLocation;
import analyzer.model.VariableChange;
import analyzer.model.VariableInfo;
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
import analyzer.phase.Phase;
import analyzer.phase.PhaseModel;
import analyzer.phase.PhaseModel.Region;
import analyzer.phase.PhaseModel.Step;
import analyzer.phase.PhaseText;
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
 *   <li>try 는 블록(subgraph) 1개: 본문은 그 안에, 예외는 블록 → catch 노드(처리 방식 요약) 간선으로 한 번에.
 *       finally 는 그 뒤 노드 1개. catch/finally 본문(과 그 안에서만 호출되는 메서드)은 생략</li>
 *   <li>실선(-->) 정상 흐름, 굵은선(==>) 본문을 전개한 메서드 호출, 점선(-.->) 처리되지 않은 throw</li>
 *   <li>연속된 요청 값 읽기 → "요청 파라미터 (n)" 노드 1개, 연속된 바인딩 코드 → "VO 값 설정" 노드 1개(필드 ← 출처). 상세는 mapping.md</li>
 * </ul>
 */
public final class FlowchartGenerator {

    public static final Map<String, String> CLASS_DEFS = new LinkedHashMap<>();
    /** 단계 보기 흐름도: 기본 + 바인딩·체크·후처리 요약 노드 */
    public static final Map<String, String> PHASE_CLASS_DEFS = new LinkedHashMap<>();

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
        CLASS_DEFS.put("bind", "fill:#ecfeff,stroke:#0e7490");
        CLASS_DEFS.put("var", "fill:#fff7ed,stroke:#ea580c");
        CLASS_DEFS.put("fin", "fill:#f1f5f9,stroke:#475569,stroke-dasharray:3 3");
        PHASE_CLASS_DEFS.putAll(CLASS_DEFS);
        PHASE_CLASS_DEFS.put("check", "fill:#fefce8,stroke:#a16207");
        PHASE_CLASS_DEFS.put("post", "fill:#f5f3ff,stroke:#6d28d9");
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
    /** 단계 보기 모드 (null 이면 일반 흐름도) */
    private final PhaseModel phases;
    private final Set<String> supCalls;
    private final Set<String> supBlocks;

    /** 세부(subgraph)로 그리는 로직 역할 — 설정 phases.logic-roles */
    private final Set<ClassRole> logicRoles;

    public FlowchartGenerator() {
        this(null, DEFAULT_LOGIC_ROLES);
    }

    /** 일반 흐름도: 세부로 그릴 로직 역할 지정 (설정 phases.logic-roles) */
    public FlowchartGenerator(Set<ClassRole> logicRoles) {
        this(null, logicRoles);
    }

    /** 설정 문자열(Service, Biz …) → 역할 */
    public static Set<ClassRole> roles(java.util.Collection<String> labels) {
        Set<ClassRole> out = java.util.EnumSet.noneOf(ClassRole.class);
        labels.forEach(l -> out.add(ClassRole.fromLabel(l).orElseThrow(() -> new IllegalArgumentException("알 수 없는 역할: " + l))));
        return out;
    }

    /**
     * 단계 보기 흐름도: 진입 메서드를 ① 바인딩(요약 1개) › ② 체크(조건 → 결과 목록) › ③ 로직(상세 흐름) › ④ 후처리(요약)로 그린다.
     * 요약 노드로 대체한 요소에서만 호출되는 메서드는 그리지 않는다.
     */
    public FlowchartGenerator(PhaseModel phases) {
        this(phases, phases == null ? DEFAULT_LOGIC_ROLES : phases.logicRoles);
    }

    private FlowchartGenerator(PhaseModel phases, Set<ClassRole> logicRoles) {
        this.logicRoles = logicRoles;
        this.phases = phases;
        this.supCalls = phases == null ? Set.of() : phases.suppressedCalls;
        this.supBlocks = phases == null ? Set.of() : phases.suppressedBlocks;
    }

    /** x = call() 형태의 변수 변경: 호출 ID → 변경 (호출 노드에 "→ x" 로 표시) */
    private final Map<String, List<VariableChange>> assignedBy = new HashMap<>();

    /** 설정이 없을 때의 로직 역할 (설정 phases.logic-roles 기본값과 같음) */
    public static final Set<ClassRole> DEFAULT_LOGIC_ROLES = java.util.EnumSet.of(ClassRole.SERVICE, ClassRole.BIZ, ClassRole.DAO,
            ClassRole.REPOSITORY, ClassRole.MAPPER);

    /** 접은 내부 함수(와 그 안에서 전개된 접힌 함수)가 값을 채우는 VO 클래스 */
    private List<String> helperObjects(String methodId) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(methodId));
        while (!todo.isEmpty()) {
            String m = todo.pop();
            if (!seen.add(m)) {
                continue;
            }
            for (CallSiteInfo c : r.callSitesOf(m)) {
                if (c.targetMethodId != null && collapsed.contains(c.expansionTargetId())
                        && (c.expansion == Expansion.EXPANDED || c.expansion == Expansion.SHARED)) {
                    todo.push(c.expansionTargetId());
                }
            }
        }
        return r.bindings.stream().filter(b -> seen.contains(b.methodId)).map(b -> r.object(b.objectId).className)
                .distinct().toList();
    }

    /**
     * 값 조회: 결과를 변수에 받는 호출인데 본문을 전개하지 않은 source 메서드(Service·DAO 등 로직 역할 제외).
     * 예: user = getLoginUser(request) — 내부로 들어가지 않고 바인딩 한 줄로 끝낸다.
     */
    private boolean fetch(CallSiteInfo s) {
        if (s.targetMethodId == null || s.expansion != Expansion.DEPTH_LIMIT) {
            return false;
        }
        MethodInfo t = r.method(s.targetMethodId);
        return t.origin == analyzer.model.Origin.SOURCE && !logicRoles.contains(r.classOf(t).role)
                && r.classOf(t).role != ClassRole.VO && r.classOf(t).role != ClassRole.UTIL;
    }

    /** 단계 보기: 세부를 그리지 않는 Action 쪽 보조 메서드 (Service·DAO 등 로직 역할 호출에 닿지 않음) */
    private Set<String> collapsed = Set.of();
    private final Set<String> drawnMethods = new java.util.HashSet<>();
    private final Map<String, Subgraph> layers = new LinkedHashMap<>();

    /** 단계 보기 레이어: 진입 클래스 쪽(Action) / Service·Biz / DAO·Mapper·Repository */
    private Subgraph layer(MethodInfo m) {
        ClassRole role = r.classOf(m).role;
        String key;
        String label;
        switch (role) {
            case SERVICE, BIZ -> {
                key = "SERVICE";
                label = "Service · Biz 레이어";
            }
            case DAO, MAPPER, REPOSITORY -> {
                key = "DAO";
                label = "DAO · Mapper 레이어";
            }
            default -> {
                key = "ACTION";
                label = r.clazz(r.targetClassId).role.label() + " 레이어";
            }
        }
        return layers.computeIfAbsent(key, k -> g.subgraph(g.root(), "sg_LAYER_" + k, label));
    }

    /**
     * 로직 역할(Service·Biz·DAO·Mapper …)이 아닌 source 메서드 중 본문에서(전이적으로) 로직 역할 호출에 닿지 않는 것.
     * 단계 보기에서는 세부(subgraph)를 그리지 않고 호출 노드만 둔다 (예: 합계 계산·문자열 정리 같은 보조 메서드).
     */
    public static Set<String> collapsedHelpers(AnalysisResult r, Set<ClassRole> logicRoles) {
        Map<String, Boolean> memo = new HashMap<>();
        Set<String> out = new java.util.HashSet<>();
        for (MethodInfo m : r.methods()) {
            if (m.analyzed && !m.id.equals(r.entryMethodId) && !logicRoles.contains(r.classOf(m).role)
                    && !reachesLogic(r, m.id, logicRoles, memo, new java.util.HashSet<>())) {
                out.add(m.id);
            }
        }
        return out;
    }

    private static boolean reachesLogic(AnalysisResult r, String methodId, Set<ClassRole> logicRoles,
                                        Map<String, Boolean> memo, Set<String> visiting) {
        Boolean known = memo.get(methodId);
        if (known != null) {
            return known;
        }
        if (!visiting.add(methodId)) {
            return false;
        }
        boolean hit = false;
        for (CallSiteInfo s : r.callSitesOf(methodId)) {
            if (s.targetMethodId == null || s.controlBlockId != null && inHandler(r, s.controlBlockId)) {
                continue;
            }
            for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
                if (t != null && logicRoles.contains(r.classOf(r.method(t)).role)) {
                    hit = true;
                }
            }
            String x = s.expansionTargetId();
            if (!hit && r.method(x).analyzed && reachesLogic(r, x, logicRoles, memo, visiting)) {
                hit = true;
            }
            if (hit) {
                break;
            }
        }
        memo.put(methodId, hit);
        return hit;
    }

    public MermaidGraph build(AnalysisResult result) {
        this.r = result;
        this.g = new MermaidGraph();
        methodGraphs.clear();
        methodParents.clear();
        assignedBy.clear();
        for (VariableChange c : r.variableChanges) {
            if ("ASSIGN".equals(c.op) || "INIT".equals(c.op)) {
                c.callSiteIds.stream().map(r::callSite)
                        .filter(s -> s.expression.equals(c.valueExpression) && !FlowView.hidden(r, s) && !requestRead(s))
                        .findFirst().ifPresent(s -> assignedBy.computeIfAbsent(s.id, k -> new ArrayList<>()).add(c));
            }
        }
        collapsed = collapsedHelpers(r, logicRoles);
        Set<String> skip = skippedMethods(r, supCalls, collapsed);
        drawnMethods.clear();
        r.methods().stream().filter(m -> m.analyzed && !skip.contains(m.id)).forEach(m -> drawnMethods.add(m.id));
        layers.clear();
        for (MethodInfo m : r.methods()) {
            if (!m.analyzed || skip.contains(m.id)) {
                continue;
            }
            String cls = r.classOf(m).name;
            Subgraph parent = phases == null ? g.root() : layer(m);
            methodParents.put(m.id, parent);
            Subgraph ms = g.subgraph(parent, "sg_" + m.id, cls + "." + m.name + "() «" + r.classOf(m).role.label() + "»");
            methodGraphs.put(m.id, ms);
            List<String> lines = new ArrayList<>(List.of(cls + "." + m.signature, m.visibility.text() + " · D" + m.depth));
            String req = Labels.methodRequestTag(r, m);
            if (req != null) {
                lines.add(Labels.truncate(req, 120));
            }
            if (phases != null && m.id.equals(r.entryMethodId)) {
                lines.addAll(exceptionNotes(r, m)); // 단계 보기 진입 메서드는 try 본문을 단계별로 펼치므로 요약 유지
            }
            g.node(ms, m.id, lines, Shape.STADIUM, m.id.equals(r.entryMethodId) ? "entry" : "method");
            if (phases != null && m.id.equals(r.entryMethodId)) {
                emitPhased(ms, m);
            } else {
                emit(ms, m, null, List.of(new End(m.id)));
            }
        }
        return g;
    }

    public String render(AnalysisResult result, String direction) {
        return render(result, direction, "dagre");
    }

    /** layout: dagre(기본, Mermaid 기본) | elk */
    public String render(AnalysisResult result, String direction, String layout) {
        MermaidGraph graph = build(result);
        List<String> errors = new ArrayList<>(graph.validate());
        errors.addAll(checkMapping(result, graph, supCalls, supBlocks, collapsed));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        return graph.render(direction, layout, phases == null ? CLASS_DEFS : PHASE_CLASS_DEFS);
    }

    /** YAML ID ↔ Mermaid 노드 매핑 검증 (흐름에서 뺀 바인딩 코드는 제외, else/case 는 간선으로 표현) */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph) {
        return checkMapping(r, graph, Set.of(), Set.of(), collapsedHelpers(r, DEFAULT_LOGIC_ROLES));
    }

    /** suppressedCalls/Blocks: 단계 보기에서 요약 노드로 대체한 진입 메서드 요소 */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph, Set<String> suppressedCalls,
                                            Set<String> suppressedBlocks) {
        return checkMapping(r, graph, suppressedCalls, suppressedBlocks, collapsedHelpers(r, DEFAULT_LOGIC_ROLES));
    }

    /** collapsed: 단계 보기에서 세부를 그리지 않는 보조 메서드 */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph, Set<String> suppressedCalls,
                                            Set<String> suppressedBlocks, Set<String> collapsed) {
        List<String> e = new ArrayList<>();
        Set<String> skip = skippedMethods(r, suppressedCalls, collapsed);
        r.methods().stream().filter(m -> m.analyzed && !skip.contains(m.id)).forEach(m -> {
            if (!graph.has(m.id)) {
                e.add("메서드 노드 누락: " + m.id);
            }
        });
        r.callSites.stream().filter(s -> drawn(r, skip, suppressedCalls, s)).forEach(s -> {
            if (!graph.has(s.id)) {
                e.add("호출 노드 누락: " + s.id);
            }
        });
        r.blocks.stream().filter(b -> !FlowView.hidden(r, b) && !skip.contains(b.methodId) && !inHandler(r, b.id)
                        && !suppressedBlocks.contains(b.id)
                        && b.type != BlockType.ELSE && b.type != BlockType.CASE && b.type != BlockType.TRY)
                .forEach(b -> {
                    if (!graph.has(b.id)) {
                        e.add("블록 노드 누락: " + b.id);
                    }
                });
        // 변수 값 변경: 그리는 메서드·블록 안이면 "값 변경" 노드 또는 호출 노드(→ x)에 매핑되어야 함
        r.variableChanges.stream().filter(c -> c.isChange() && r.method(c.methodId).analyzed && !skip.contains(c.methodId)
                        && (c.controlBlockId == null || !inHandler(r, c.controlBlockId) && !suppressedBlocks.contains(c.controlBlockId))
                        && !suppressedCalls.contains(c.id))
                .forEach(c -> {
                    if (!graph.has(c.id)) {
                        e.add("변수 변경 노드 누락: " + c.id);
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
    public static boolean inHandler(AnalysisResult r, String blockId) {
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
    private static boolean drawn(AnalysisResult r, Set<String> skip, Set<String> suppressed, CallSiteInfo s) {
        return !FlowView.hidden(r, s) && !skip.contains(s.callerMethodId) && !suppressed.contains(s.id)
                && (s.controlBlockId == null || !inHandler(r, s.controlBlockId));
    }

    /** 흐름도에 노드로 그리는 호출인지 (테스트·검증용) */
    public static boolean drawsCall(AnalysisResult r, CallSiteInfo s) {
        return drawn(r, skippedMethods(r), Set.of(), s);
    }

    /** 그리지 않는 메서드: 바인딩 메서드 + catch/finally 안에서만 호출되는 메서드(전이적으로) */
    public static Set<String> skippedMethods(AnalysisResult r) {
        return skippedMethods(r, Set.of(), collapsedHelpers(r, DEFAULT_LOGIC_ROLES));
    }

    /** suppressed: 그리지 않는 진입 메서드 호출 (단계 보기의 요약 대상) */
    public static Set<String> skippedMethods(AnalysisResult r, Set<String> suppressed) {
        return skippedMethods(r, suppressed, Set.of());
    }

    /** seed: 처음부터 그리지 않을 메서드 (단계 보기의 접힌 보조 메서드) — 그 안에서만 호출되는 메서드도 전이적으로 생략 */
    public static Set<String> skippedMethods(AnalysisResult r, Set<String> suppressed, Set<String> seed) {
        Set<String> skip = new java.util.HashSet<>(FlowView.bindingMethods(r));
        skip.addAll(seed);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo m : r.methods()) {
                if (!m.analyzed || skip.contains(m.id) || m.id.equals(r.entryMethodId)) {
                    continue;
                }
                List<CallSiteInfo> in = r.callSites.stream()
                        .filter(s -> s.targetMethodId != null && m.id.equals(s.expansionTargetId())).toList();
                if (!in.isEmpty() && in.stream().noneMatch(s -> drawn(r, skip, suppressed, s))) {
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

    // ------------------------------------------------------------------ phased entry

    /** 진입 메서드: ① 바인딩 요약 › ② 체크 목록 › ③ 로직 상세(사이의 바인딩·체크는 요약 노드) › ④ 후처리 요약 › 종료 */
    private void emitPhased(Subgraph ms, MethodInfo m) {
        List<End> cur = List.of(new End(m.id));
        List<Step> pre = phases.region(Region.PRE);
        List<Step> preBinding = pre.stream().filter(s -> s.phase != Phase.CHECK && s.phase != Phase.END).toList();
        if (!preBinding.isEmpty()) {
            Subgraph sb = g.subgraph(ms, "sg_PH_BIND", Phase.BINDING.title());
            g.node(sb, "PH_BIND", PhaseText.bindingLines(r, pre), Shape.RECT, "bind");
            cur = link(cur, "PH_BIND");
        }
        List<PhaseModel.CheckItem> checks = phases.preChecks();
        if (!checks.isEmpty()) {
            Subgraph sc = g.subgraph(ms, "sg_PH_CHECK", Phase.CHECK.title());
            int i = 0;
            for (PhaseModel.CheckItem c : checks) {
                String id = "PH_CHECK" + (++i);
                g.node(sc, id, PhaseText.checkLines(c), Shape.RECT, "check");
                link(cur, id);
                cur = List.of(new End(id, "pass", EdgeStyle.FLOW));
            }
        }
        Subgraph sl = null;
        List<Step> pending = new ArrayList<>();
        int inline = 0;
        for (Step st : phases.region(Region.LOGIC)) {
            if (sl == null) {
                sl = g.subgraph(ms, "sg_PH_LOGIC", Phase.LOGIC.title());
            }
            if (st.phase != Phase.LOGIC) {
                pending.add(st);
                continue;
            }
            if (!pending.isEmpty()) {
                String id = "PH_INL" + (++inline);
                g.node(sl, id, PhaseText.inlineLines(r, pending), Shape.RECT, "bind");
                cur = link(cur, id);
                pending.clear();
            }
            if (st.element instanceof CallSiteInfo s) {
                if (!FlowView.hidden(r, s)) {
                    callNode(sl, s);
                    cur = link(cur, s.id);
                }
            } else if (st.element instanceof ControlBlock b) {
                cur = block(sl, m, b, cur);
            } else if (st.element instanceof VariableChange c) {
                cur = flushVars(sl, new ArrayList<>(List.of(c)), cur);
            }
            // 같은 문장의 값 변경(count = count + svc.get() 등)이 아직 안 그려졌으면 호출 뒤에 값 변경 노드로
            List<VariableChange> rest = new ArrayList<>(st.changes.stream()
                    .filter(c -> c.isChange() && !g.has(c.id) && !supCalls.contains(c.id)).toList());
            cur = flushVars(sl, rest, cur);
        }
        List<Step> post = phases.region(Region.POST).stream().filter(s -> s.phase == Phase.POST).toList();
        if (!post.isEmpty()) {
            Subgraph sp = g.subgraph(ms, "sg_PH_POST", Phase.POST.title());
            g.node(sp, "PH_POST", PhaseText.inlineLines(r, post), Shape.RECT, "post");
            cur = link(cur, "PH_POST");
        }
        for (Step st : phases.steps) {
            if (st.phase == Phase.END && st.element instanceof ControlBlock b) {
                cur = block(ms, m, b, cur);
            }
        }
    }

    // ------------------------------------------------------------------ flow

    /** 연속된 요약 대상 (한 번에 한 종류만 쌓인다): 요청 값 읽기 / 바인딩 코드 / 변수 값 변경 */
    private final class Pending {
        final Subgraph sg;
        final MethodInfo m;
        final String container;
        final List<CallSiteInfo> reads = new ArrayList<>();
        final List<Object> sets = new ArrayList<>();
        final List<VariableChange> vars = new ArrayList<>();

        Pending(Subgraph sg, MethodInfo m, String container) {
            this.sg = sg;
            this.m = m;
            this.container = container;
        }

        List<End> flush(List<End> cur) {
            cur = flushReads(sg, reads, cur);
            cur = flushSets(sg, m, container, sets, cur);
            return flushVars(sg, vars, cur);
        }
    }

    private List<End> emit(Subgraph sg, MethodInfo m, ControlBlock parent, List<End> in) {
        Set<String> header = parent == null ? Set.of() : headerPositions(parent.type);
        String container = parent == null ? null : parent.id;
        List<End> cur = in;
        Pending p = new Pending(sg, m, container);
        List<Object> elems = Labels.elements(r, m.id, container, false);
        // 호출 없이 일어나는 바인딩(생성자 인자가 리터럴뿐 등)은 위치 순으로 VO 값 설정에 끼운다
        List<Binding> orphans = new ArrayList<>(r.bindings.stream().filter(b -> b.methodId.equals(m.id)
                && java.util.Objects.equals(b.controlBlockId, container) && b.callSiteId == null
                && elems.stream().noneMatch(o -> o instanceof CallSiteInfo s && FlowView.hidden(r, s)
                && contains(s.location, b.location))).toList());
        // 변수 값 변경: 식 안의 호출이 끝난 뒤(평가 순서). x = call() 은 호출 노드에 "→ x" 로 표시하므로 제외
        // 선언(1/n)도 이력의 시작이므로 그린다 — 요청 읽기 노드·호출 노드에 이미 표시된 것은 제외
        List<VariableChange> changes = new ArrayList<>(r.variableChanges.stream()
                .filter(c -> c.methodId.equals(m.id) && java.util.Objects.equals(c.controlBlockId, container))
                .filter(c -> c.isChange() || "INIT".equals(c.op) && c.valueExpression != null && !initShownAsRead(c))
                .filter(c -> assignedBy.values().stream().noneMatch(l -> l.contains(c)))
                .filter(c -> !supCalls.contains(c.id)).toList());
        for (Object o : elems) {
            SourceLocation at = o instanceof CallSiteInfo s ? s.location : ((ControlBlock) o).location;
            while (!orphans.isEmpty() && before(orphans.get(0).location, at)) {
                cur = flushExcept(p, "set", cur);
                p.sets.add(orphans.remove(0));
            }
            while (!changes.isEmpty() && endsBefore(changes.get(0).location, at)) {
                cur = flushExcept(p, "var", cur);
                p.vars.add(changes.remove(0));
            }
            if (o instanceof CallSiteInfo s) {
                if (header.contains(s.position)) {
                    continue;
                }
                if (FlowView.hidden(r, s)) { // 바인딩 코드(setter/builder/put, 그 인자 속 호출, 바인딩 메서드 호출)
                    cur = flushExcept(p, "set", cur);
                    p.sets.add(s);
                    continue;
                }
                if (requestRead(s)) {
                    cur = flushExcept(p, "read", cur);
                    p.reads.add(s);
                    continue;
                }
                cur = p.flush(cur);
                callNode(sg, s);
                cur = link(cur, s.id);
            } else {
                cur = p.flush(cur);
                cur = block(sg, m, (ControlBlock) o, cur);
            }
        }
        if (!orphans.isEmpty()) {
            cur = flushExcept(p, "set", cur);
            p.sets.addAll(orphans);
        }
        if (!changes.isEmpty()) {
            cur = flushExcept(p, "var", cur);
            p.vars.addAll(changes);
        }
        return p.flush(cur);
    }

    /** int size = XxxUtil.getInt(request, …) 처럼 선언 값이 요청 읽기 호출 그 자체 → 요청 파라미터 노드에 "· V1 1/n" 으로 표시됨 */
    private boolean initShownAsRead(VariableChange c) {
        return c.callSiteIds.stream().map(r::callSite)
                .anyMatch(x -> x.expression.equals(c.valueExpression) && requestRead(x) && !FlowView.hidden(r, x));
    }

    /** kind 가 아닌 쌓인 요약을 먼저 내보낸다 */
    private List<End> flushExcept(Pending p, String kind, List<End> cur) {
        if (!kind.equals("read")) {
            cur = flushReads(p.sg, p.reads, cur);
        }
        if (!kind.equals("set")) {
            cur = flushSets(p.sg, p.m, p.container, p.sets, cur);
        }
        if (!kind.equals("var")) {
            cur = flushVars(p.sg, p.vars, cur);
        }
        return cur;
    }

    /** 연속된 변수 값 변경 → "값 변경" 노드 1개 (ID = VAR_첫변경ID, 나머지 변경 ID 는 alias) */
    private List<End> flushVars(Subgraph sg, List<VariableChange> vars, List<End> cur) {
        if (vars.isEmpty()) {
            return cur;
        }
        List<String> lines = new ArrayList<>(List.of("값 변경"));
        vars.forEach(c -> lines.add(Labels.changeLine(r, c)));
        String id = "VAR_" + vars.get(0).id;
        g.node(sg, id, lines, Shape.RECT, "var");
        vars.forEach(c -> g.alias(c.id, id));
        vars.clear();
        return link(cur, id);
    }

    /** a 가 끝난 뒤에 b 가 시작하는지 */
    private static boolean endsBefore(SourceLocation a, SourceLocation b) {
        return a != null && b != null && (a.endLine() < b.beginLine()
                || a.endLine() == b.beginLine() && a.endColumn() < b.beginColumn());
    }

    /**
     * 연속된 바인딩 코드 → "VO 값 설정" 노드 1개 (ID = SET_첫요소ID). 객체별로 "필드 ← 출처" 를 나열한다.
     * 바인딩 메서드 호출(VO 를 채워 반환하는 메서드)은 그 메서드의 바인딩을 포함한다. 상세는 mapping.md.
     */
    private List<End> flushSets(Subgraph sg, MethodInfo m, String container, List<Object> sets, List<End> cur) {
        if (sets.isEmpty()) {
            return cur;
        }
        List<Binding> bs = new ArrayList<>();
        for (Object o : sets) {
            if (o instanceof Binding b) {
                bs.add(b);
                continue;
            }
            CallSiteInfo s = (CallSiteInfo) o;
            for (Binding b : r.bindings) {
                boolean here = b.methodId.equals(m.id) && java.util.Objects.equals(b.controlBlockId, container)
                        && (s.id.equals(b.callSiteId) || contains(s.location, b.location));
                boolean viaMethod = s.targetMethodId != null && FlowView.bindingMethods(r).contains(s.expansionTargetId())
                        && b.methodId.equals(s.expansionTargetId());
                if ((here || viaMethod) && !bs.contains(b)) {
                    bs.add(b);
                }
            }
        }
        String first = sets.get(0) instanceof CallSiteInfo s ? s.id : ((Binding) sets.get(0)).id;
        sets.clear();
        if (bs.isEmpty()) {
            return cur;
        }
        Map<String, List<Binding>> byObject = new LinkedHashMap<>();
        bs.forEach(b -> byObject.computeIfAbsent(b.objectId, k -> new ArrayList<>()).add(b));
        List<String> lines = new ArrayList<>(List.of("VO 값 설정 · " + String.join(", ", byObject.keySet().stream()
                .map(k -> r.object(k).className).distinct().toList())));
        int shown = 0;
        for (var e : byObject.entrySet()) {
            String via = e.getValue().stream().map(b -> b.methodId).filter(x -> !x.equals(m.id)).findFirst().orElse(null);
            if (via != null) { // 바인딩 메서드(VO 를 채워 반환) 안에서 채움
                lines.add(r.method(via).name + "() 안:");
            }
            for (Binding b : e.getValue()) {
                if (shown++ < SET_LINES) {
                    lines.add(Labels.bindingLine(r, b, b.methodId.equals(m.id)));
                }
            }
        }
        if (shown > SET_LINES) {
            lines.add("… 외 " + (shown - SET_LINES) + "개 (mapping.md)");
        }
        String id = "SET_" + first;
        g.node(sg, id, lines, Shape.RECT, "bind");
        return link(cur, id);
    }

    private static final int SET_LINES = 10;

    private static boolean before(SourceLocation a, SourceLocation b) {
        return a != null && b != null && (a.beginLine() < b.beginLine()
                || a.beginLine() == b.beginLine() && a.beginColumn() < b.beginColumn());
    }

    private static boolean contains(SourceLocation outer, SourceLocation inner) {
        if (outer == null || inner == null || !java.util.Objects.equals(outer.file(), inner.file())) {
            return false;
        }
        boolean begin = outer.beginLine() < inner.beginLine()
                || outer.beginLine() == inner.beginLine() && outer.beginColumn() <= inner.beginColumn();
        boolean end = inner.endLine() < outer.endLine()
                || inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn();
        return begin && end;
    }

    /** 본문을 전개하지 않는 요청 값 읽기 (getParameter*, request-parameter-rules 의 유틸 …) */
    private boolean requestRead(CallSiteInfo s) {
        return !r.parametersAt(s.id).isEmpty() && s.expansion != Expansion.EXPANDED
                && s.expansion != Expansion.SHARED && s.expansion != Expansion.CYCLE;
    }

    /** 연속된 요청 값 읽기 → 요약 노드 1개 "getParam: a * b * c" (노드 ID = 첫 호출 ID, 나머지는 alias) */
    private List<End> flushReads(Subgraph sg, List<CallSiteInfo> reads, List<End> cur) {
        if (reads.isEmpty()) {
            return cur;
        }
        // 한 줄에 하나: #2 status / #3 page / #4 size
        List<String> lines = new ArrayList<>(List.of("요청 파라미터"));
        for (CallSiteInfo s : reads) {
            r.parametersAt(s.id).forEach(p -> lines.add(Labels.requestLine(r, s, p)));
        }
        CallSiteInfo first = reads.get(0);
        g.node(sg, first.id, lines, Shape.RECT, "req");
        reads.stream().skip(1).forEach(s -> g.alias(s.id, first.id));
        reads.clear();
        return link(cur, first.id);
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
                return tryBlock(sg, m, b, in);
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
                if (requestRead(s)) { // 조건식 안 요청 읽기도 코드 형식 요청 파라미터 노드
                    cur = flushReads(sg, new ArrayList<>(List.of(s)), cur);
                } else {
                    callNode(sg, s);
                    cur = link(cur, s.id);
                }
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

    /**
     * try = 블록(subgraph) 하나. 본문은 그 안에 정상 흐름으로, 예외는 블록 단위로 catch 노드에 연결한다
     * (try 블록 -.->|예외 타입| catch). 개별 throw 에는 catch 표시를 하지 않는다.
     * catch 노드 = 처리 방식 요약(로그·오류 응답·재발생·새 예외·return). 다시 던지면 uncaught 로, return 이면 끝, 그 밖이면 try 뒤로 이어짐.
     * finally 는 try·catch 가 끝난 뒤 지나는 노드 1개(안의 호출식 요약).
     */
    private List<End> tryBlock(Subgraph sg, MethodInfo m, ControlBlock t, List<End> in) {
        Subgraph ts = g.subgraph(sg, "sg_" + t.id, Labels.blockHeader(t));
        List<End> out = new ArrayList<>(emit(ts, m, t, in));
        for (String cid : t.catchBlockIds) {
            ControlBlock c = r.block(cid);
            List<String> how = new ArrayList<>();
            if (!c.logCalls.isEmpty()) {
                how.add("로그");
            }
            if (!c.errorResponseCalls.isEmpty()) {
                how.add("오류 응답");
            }
            if (c.rethrows) {
                how.add("다시 던짐");
            } else if (c.throwsNewException) {
                how.add("새 예외 던짐");
            }
            // catch 본문 최상위의 return → 여기서 끝 (finally 만 지나 메서드 종료)
            ControlBlock ret = c.childBlockIds.stream().map(r::block)
                    .filter(x -> x.type == BlockType.RETURN && c.id.equals(x.parentBlockId)).findFirst().orElse(null);
            if (ret != null) {
                how.add(Labels.blockHeader(ret));
            } else if (c.containsReturn) {
                how.add("return");
            }
            List<String> lines = new ArrayList<>(List.of(Labels.blockHeader(c)));
            if (!how.isEmpty()) {
                lines.add(String.join(" · ", how));
            }
            g.node(sg, c.id, lines, Shape.SUBROUTINE, "exc");
            g.edge(ts.id(), c.id, String.join(" | ", c.exceptionTypes), EdgeStyle.EXCEPTION);
            if (c.rethrows || c.throwsNewException) {
                String exit = m.id + "_uncaught";
                if (!g.has(exit)) {
                    g.node(methodGraphs.get(m.id), exit, List.of("uncaught → caller"), Shape.SUBROUTINE, "exc");
                }
                g.edge(c.id, exit, "throw", EdgeStyle.EXCEPTION);
            } else if (ret == null && !c.containsReturn) {
                out.add(new End(c.id));
            }
        }
        if (t.finallyBlockId != null) {
            ControlBlock f = r.block(t.finallyBlockId);
            List<String> lines = new ArrayList<>(List.of("finally"));
            List<CallSiteInfo> fc = r.callSites.stream().filter(x -> x.callerMethodId.equals(m.id) && x.controlBlockId != null
                    && x.controlBlockId.equals(f.id)).toList();
            fc.stream().filter(x -> fc.stream().noneMatch(o -> o != x && contains(o.location, x.location))) // 바깥 호출만
                    .limit(3).forEach(x -> lines.add(Labels.truncate(x.expression, 60)));
            g.node(sg, f.id, lines, Shape.RECT, "fin");
            return link(out, f.id);
        }
        return out;
    }

    private final Map<String, Subgraph> methodParents = new HashMap<>();

    private void leaf(Subgraph sg, MethodInfo m, ControlBlock b) {
        List<String> lines = new ArrayList<>(List.of(Labels.blockHeader(b)));
        if (b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED) {
            lines.add("(catch 경로 unresolved)");
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
        boolean folded = s.targetMethodId != null && collapsed.contains(s.expansionTargetId());
        String var = s.resultUsage != null && s.resultUsage.startsWith("assigned:") ? s.resultUsage.substring(9) : null;
        if (var != null && fetch(s)) {
            // 값 조회는 바인딩으로 끝: 내부로 들어가지 않고 코드 한 줄 (#1 user = getLoginUser(request))
            g.node(sg, s.id, List.of("#" + s.order + " " + var + " = " + Labels.truncate(s.expression, 70)), Shape.RECT, "bind");
            return;
        }
        if (folded) {
            // 내부 함수(Service·DAO 로 가지 않는 보조 메서드)는 세부 없이 코드 한 줄 (#3 items = readItems(request))
            StringBuilder code = new StringBuilder("#" + s.order + " " + (var == null ? "" : var + " = ")
                    + Labels.truncate(s.expression, 70));
            for (VariableChange c : assignedBy.getOrDefault(s.id, List.of())) { // 값이 바뀌는 변수면 이력 순번
                VariableInfo v = r.variable(c.variableId);
                code.append(v.name.equals(var) ? "" : " → " + v.name).append(" · ").append(v.id).append(' ')
                        .append(c.order).append('/').append(v.changeIds.size());
            }
            lines.add(code.toString());
            List<String> vos = helperObjects(s.expansionTargetId());
            if (!vos.isEmpty()) {
                lines.add("VO 값 설정 · " + String.join(", ", vos));
            }
            g.node(sg, s.id, lines, Shape.RECT, vos.isEmpty() ? null : "bind");
            assignedBy.getOrDefault(s.id, List.of()).forEach(c -> g.alias(c.id, s.id));
            return;
        }
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
        for (VariableChange c : assignedBy.getOrDefault(s.id, List.of())) {
            VariableInfo v = r.variable(c.variableId);
            lines.add("→ " + v.name + " · " + v.id + " " + c.order + "/" + v.changeIds.size());
        }
        String css = switch (s.expansion) {
            case NOT_APPLICABLE -> "unresolved";
            case CYCLE -> "cycle";
            case DEPTH_LIMIT -> req != null ? "req" : "depth";
            case EXTERNAL, NO_BODY -> req != null ? "req" : s.resolution == ResolutionStatus.RESOLVED ? "external" : "unresolved";
            default -> req != null ? "req" : s.resolution == ResolutionStatus.RESOLVED ? null : "unresolved";
        };
        g.node(sg, s.id, lines, Shape.RECT, css);
        assignedBy.getOrDefault(s.id, List.of()).forEach(c -> g.alias(c.id, s.id));
        if (s.targetMethodId != null && drawnMethods.contains(s.expansionTargetId())
                && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED
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
