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
import analyzer.model.LogCalls;
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
        CLASS_DEFS.put("golink", "fill:#f8fafc,stroke:#64748b,stroke-dasharray:5 3");
        CLASS_DEFS.put("codeline", "fill:#eef2ff,stroke:#6366f1");
        CLASS_DEFS.put("fin", "fill:#f1f5f9,stroke:#475569,stroke-dasharray:3 3");
        CLASS_DEFS.put("state", "fill:#fff7ed,stroke:#ea580c");
        CLASS_DEFS.put("log", "fill:#e2e8f0,stroke:#64748b,color:#334155,stroke-dasharray:2 2");
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
    /** 그리지 않는 메서드 (build 에서 계산) */
    private Set<String> skipSet = Set.of();
    /** return/throw 리프에 포함시켜 따로 그리지 않는 호출 */
    private final Set<String> inLeaf = new java.util.HashSet<>();

    /** 세부 박스(그리는 메서드)로 이어지는 호출 — ==> 간선이 필요해 노드로 남긴다 */
    private boolean leadsToDetail(CallSiteInfo c) {
        return c.targetMethodId != null && drawnMethods.contains(c.expansionTargetId())
                && (c.expansion == Expansion.EXPANDED || c.expansion == Expansion.SHARED || c.expansion == Expansion.CYCLE);
    }
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
        inLeaf.clear();
        assignedBy.clear();
        for (VariableChange c : r.variableChanges) {
            if ("ASSIGN".equals(c.op) || "INIT".equals(c.op)) {
                c.callSiteIds.stream().map(r::callSite)
                        .filter(s -> s.expression.equals(c.valueExpression) && !FlowView.hidden(r, s) && !requestRead(s))
                        .findFirst().ifPresent(s -> assignedBy.computeIfAbsent(s.id, k -> new ArrayList<>()).add(c));
            }
        }
        // 일반 흐름도는 보조 메서드도 접지 않는다 (빠진 코드 없음) — 접기는 단계 보기에서만
        collapsed = phases == null ? Set.of() : collapsedHelpers(r, logicRoles);
        rules = phases == null ? Rules.normal(r, excludes) : Rules.phase(supCalls, supBlocks, collapsed, excludes);
        bindingFns = phases == null ? bindingFunctions(r) : Set.of();
        Set<String> skip = skippedMethods(r, rules);
        skipSet = skip;
        if (!excludes.isEmpty()) {
            g.comment("flowchart-exclude (사용자 설정으로 뺀 호출·메서드): " + String.join(", ", excludes));
        }
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
        errors.addAll(check(result, graph, rules));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        return graph.maxLineLength(maxLineLength).render(direction, layout, phases == null ? CLASS_DEFS : PHASE_CLASS_DEFS);
    }

    private int maxLineLength = 100;
    /** 사용자 설정 output.flowchart-exclude */
    private List<String> excludes = List.of();
    private Rules rules;
    /** 바인딩 블록 안에 중첩해서 보이는 바인딩 함수 (일반 흐름도) */
    private Set<String> bindingFns = Set.of();

    /** 흐름도에서 뺄 호출·메서드 패턴 (설정 output.flowchart-exclude, 사용자 관리) */
    public FlowchartGenerator exclude(List<String> patterns) {
        this.excludes = patterns == null ? List.of() : List.copyOf(patterns);
        return this;
    }

    /** 분할 출력 1개: 파일명, 제목, Mermaid 본문 */
    public record Part(String file, String title, String mermaid) {
    }

    /**
     * 흐름도를 파일별로 나눈다: 00-overview(메서드 박스 + 호출 관계) + 그리는 메서드마다 1개.
     * 다른 메서드로 넘어가는 호출은 "▶ M27 Class.method() — 파일명" 안내 노드로 끝난다. 모두 같은 모델·같은 ID.
     */
    public List<Part> renderSplit(AnalysisResult result, String direction, String layout) {
        MermaidGraph full = build(result);
        List<String> errors = new ArrayList<>(full.validate());
        errors.addAll(check(result, full, rules));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        Map<String, String> classDefs = phases == null ? CLASS_DEFS : PHASE_CLASS_DEFS;
        List<MethodInfo> drawn = r.methods().stream().filter(m -> drawnMethods.contains(m.id) && full.has("sg_" + m.id)).toList();
        Map<String, String> fileOf = new LinkedHashMap<>();
        int i = 1;
        for (MethodInfo m : drawn) {
            fileOf.put(m.id, String.format("%02d-%s-%s.%s.mmd", i++, m.id, r.classOf(m).name, m.name));
        }
        // 노드 → 소속 메서드 (호출 관계 집계용)
        Map<String, String> owner = new HashMap<>();
        for (MethodInfo m : drawn) {
            full.idsUnder("sg_" + m.id).forEach(id -> owner.putIfAbsent(id, m.id));
        }
        List<Part> parts = new ArrayList<>();
        // 00-overview
        MermaidGraph ov = new MermaidGraph().maxLineLength(maxLineLength);
        for (MethodInfo m : drawn) {
            ov.node(ov.root(), m.id, List.of(m.id + " " + r.classOf(m).name + "." + m.name + "()",
                    "«" + r.classOf(m).role.label() + "» · " + fileOf.get(m.id)), Shape.STADIUM,
                    m.id.equals(r.entryMethodId) ? "entry" : "method");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var e : full.edges()) {
            if (e.style() == EdgeStyle.CALL && owner.containsKey(e.from()) && fileOf.containsKey(e.to())) {
                String from = owner.get(e.from());
                if (!from.equals(e.to()) && seen.add(from + ">" + e.to())) {
                    ov.edge(from, e.to(), e.label(), EdgeStyle.CALL);
                }
            }
        }
        parts.add(new Part("00-overview.mmd", "전체 구조 (메서드 호출 관계)", ov.render(direction, layout, classDefs)));
        for (MethodInfo m : drawn) {
            MermaidGraph one = full.extract("sg_" + m.id, to -> {
                String target = owner.get(to);
                String where = target == null ? "" : " — " + fileOf.get(target);
                return List.of("▶ " + full.labelOf(to), (target == null ? to : target) + where);
            });
            parts.add(new Part(fileOf.get(m.id), m.id + " " + r.classOf(m).name + "." + m.name + "()",
                    one.render(direction, layout, classDefs)));
        }
        return parts;
    }

    /** 분할 파일을 한 문서로: 목차 + 파트마다 ```mermaid 블록 */
    public static String combinedMarkdown(String title, List<Part> parts) {
        StringBuilder sb = new StringBuilder("# 흐름도: ").append(title).append("\n\n");
        sb.append("> `flowchart/` 의 파일을 순서대로 합친 문서입니다. 다른 메서드로 넘어가는 호출은 ▶ 노드로 끝나고, 그 메서드는 아래 해당 절에 있습니다.\n\n");
        for (Part p : parts) {
            sb.append("- [").append(p.title()).append("](#").append(anchor(p.title())).append(")\n");
        }
        for (Part p : parts) {
            sb.append("\n## ").append(p.title()).append("\n\n`flowchart/").append(p.file()).append("`\n\n```mermaid\n")
                    .append(p.mermaid()).append("```\n");
        }
        return sb.toString();
    }

    /** GitHub 식 제목 앵커 (소문자, 공백 → -, 영숫자·한글·-·_ 외 제거) */
    private static String anchor(String title) {
        return title.toLowerCase().replace(' ', '-').replaceAll("[^\\p{L}\\p{N}_-]", "");
    }

    /** 노드 한 줄 최대 길이 (설정 output.max-line-length, 0 = 끊지 않음) */
    public FlowchartGenerator maxLineLength(int n) {
        this.maxLineLength = n;
        return this;
    }

    // ------------------------------------------------------------------ 그리기 규칙

    /**
     * 무엇을 그리는가. 일반 흐름도는 분석한 코드를 빠짐없이 그린다(catch/finally 본문 포함) — 빼는 것은 사용자 설정
     * output.flowchart-exclude 뿐. 단계 보기는 요약 보기이므로 catch/finally 본문을 생략하고 요약 노드로 대체한다.
     *
     * @param seed 처음부터 상자를 그리지 않는 메서드 (일반: 바인딩 함수 + exclude, 단계 보기: 접힌 보조 메서드)
     */
    record Rules(Set<String> suppressedCalls, Set<String> suppressedBlocks, Set<String> seed, boolean omitHandlers,
                 List<String> excludes) {

        static Rules normal(AnalysisResult r, List<String> excludes) {
            Set<String> seed = new java.util.HashSet<>(bindingFunctions(r));
            r.methods().stream().filter(m -> m.analyzed && !m.id.equals(r.entryMethodId) && excludedMethod(r, excludes, m))
                    .forEach(m -> seed.add(m.id));
            return new Rules(Set.of(), Set.of(), seed, false, excludes);
        }

        static Rules phase(Set<String> suppressedCalls, Set<String> suppressedBlocks, Set<String> collapsed, List<String> excludes) {
            return new Rules(suppressedCalls, suppressedBlocks, collapsed, true, excludes);
        }
    }

    /** YAML ID ↔ Mermaid 노드 매핑 검증 — 일반 흐름도 (빠진 코드가 없어야 함) */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph) {
        return check(r, graph, Rules.normal(r, List.of()));
    }

    /** 단계 보기: suppressedCalls/Blocks = 요약 노드로 대체한 진입 메서드 요소 */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph, Set<String> suppressedCalls,
                                            Set<String> suppressedBlocks) {
        return checkMapping(r, graph, suppressedCalls, suppressedBlocks, collapsedHelpers(r, DEFAULT_LOGIC_ROLES));
    }

    /** 단계 보기: collapsed = 세부를 그리지 않는 보조 메서드 */
    public static List<String> checkMapping(AnalysisResult r, MermaidGraph graph, Set<String> suppressedCalls,
                                            Set<String> suppressedBlocks, Set<String> collapsed) {
        return check(r, graph, Rules.phase(suppressedCalls, suppressedBlocks, collapsed, List.of()));
    }

    private static List<String> check(AnalysisResult r, MermaidGraph graph, Rules rules) {
        List<String> e = new ArrayList<>();
        Set<String> skip = skippedMethods(r, rules);
        r.methods().stream().filter(m -> m.analyzed && !skip.contains(m.id)).forEach(m -> {
            if (!graph.has(m.id)) {
                e.add("메서드 노드 누락: " + m.id);
            }
        });
        r.callSites.stream().filter(s -> drawn(r, rules, skip, s)).forEach(s -> {
            if (!graph.has(s.id)) {
                e.add("호출 노드 누락: " + s.id + " `" + s.expression + "`");
            }
        });
        r.blocks.stream().filter(b -> !FlowView.hidden(r, b) && !skip.contains(b.methodId)
                        && !(rules.omitHandlers() && inHandler(r, b.id))
                        && !rules.suppressedBlocks().contains(b.id)
                        && b.type != BlockType.ELSE && b.type != BlockType.CASE && b.type != BlockType.TRY)
                .forEach(b -> {
                    if (!graph.has(b.id)) {
                        e.add("블록 노드 누락: " + b.id);
                    }
                });
        // 변수 값 변경: 그리는 메서드·블록 안이면 "상태 변화" 노드 또는 호출 노드(→ x)에 매핑되어야 함
        r.variableChanges.stream().filter(c -> c.isChange() && r.method(c.methodId).analyzed && !skip.contains(c.methodId)
                        && (c.controlBlockId == null || !(rules.omitHandlers() && inHandler(r, c.controlBlockId))
                        && !rules.suppressedBlocks().contains(c.controlBlockId))
                        && !rules.suppressedCalls().contains(c.id))
                .forEach(c -> {
                    if (!graph.has(c.id)) {
                        e.add("변수 변경 노드 누락: " + c.id + " `" + c.expression + "` (" + r.method(c.methodId).name + " · "
                                + c.location.shortText() + ", block " + c.controlBlockId + ")");
                    }
                });
        // ==> 는 모델의 호출 대상과 일치해야 함 (상태 변화 블록처럼 여러 호출을 합친 노드는 그 중 하나)
        for (var ed : graph.edges()) {
            if (ed.style() == EdgeStyle.CALL) {
                boolean ok = graph.modelIdsOf(ed.from()).stream().map(id -> r.element(id).orElse(null))
                        .filter(o -> o instanceof CallSiteInfo).map(o -> (CallSiteInfo) o)
                        .anyMatch(s -> ed.to().equals(s.targetMethodId) || ed.to().equals(s.implementationMethodId));
                if (!ok) {
                    e.add("모델에 없는 호출 엣지: " + ed.from() + " ==> " + ed.to());
                }
            }
        }
        return e;
    }

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

    /**
     * 인자 안의 호출(log.error("…" + util.x(req)) 의 util.x)은 따로 그리지 않는다 — 바깥 호출 노드의 코드에 보인다.
     * 단 세부 박스로 이어지는 호출(==> 필요)과 요청 읽기(바인딩)는 그리고, 바깥 호출이 모델에 없으면(외부 라이브러리 제외,
     * 생성자 인자 등) 그 코드가 사라지지 않도록 그린다.
     */
    static boolean argumentOnly(AnalysisResult r, Set<String> skip, CallSiteInfo s) {
        if (!s.inArgument) {
            return false;
        }
        boolean detailed = s.targetMethodId != null && !skip.contains(s.expansionTargetId())
                && r.method(s.expansionTargetId()).analyzed
                && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED || s.expansion == Expansion.CYCLE);
        boolean read = !r.parametersAt(s.id).isEmpty();
        if (detailed || read) {
            return false;
        }
        return r.callSitesOf(s.callerMethodId).stream().anyMatch(o -> o != s && contains(o.location, s.location));
    }

    private static boolean drawn(AnalysisResult r, Rules rules, Set<String> skip, CallSiteInfo s) {
        return !FlowView.hidden(r, s) && !skip.contains(s.callerMethodId) && !rules.suppressedCalls().contains(s.id)
                && !argumentOnly(r, skip, s)
                && !(rules.omitHandlers() && s.controlBlockId != null && inHandler(r, s.controlBlockId))
                && !excludedCall(r, rules.excludes(), s);
    }

    /** 일반 흐름도에 그리는 호출인지 (노드 또는 블록 줄) — 테스트·검증용 */
    public static boolean drawsCall(AnalysisResult r, CallSiteInfo s) {
        Rules rules = Rules.normal(r, List.of());
        return drawn(r, rules, skippedMethods(r, rules), s);
    }

    /** 일반 흐름도에서 상자를 그리지 않는 메서드: 바인딩 함수(바인딩 블록 안에 중첩 표시) */
    public static Set<String> skippedMethods(AnalysisResult r) {
        return skippedMethods(r, Rules.normal(r, List.of()));
    }

    /** 단계 보기: suppressed = 그리지 않는 진입 메서드 호출 (요약 대상) */
    public static Set<String> skippedMethods(AnalysisResult r, Set<String> suppressed) {
        return skippedMethods(r, Rules.phase(suppressed, Set.of(), Set.of(), List.of()));
    }

    /** 단계 보기: seed = 처음부터 그리지 않을 메서드 (접힌 보조 메서드) */
    public static Set<String> skippedMethods(AnalysisResult r, Set<String> suppressed, Set<String> seed) {
        return skippedMethods(r, Rules.phase(suppressed, Set.of(), seed, List.of()));
    }

    /** seed + 그 안에서만(또는 그리지 않는 호출에서만) 호출되는 메서드 — 전이적으로 */
    private static Set<String> skippedMethods(AnalysisResult r, Rules rules) {
        Set<String> skip = new java.util.HashSet<>(FlowView.bindingMethods(r));
        skip.addAll(rules.seed());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodInfo m : r.methods()) {
                if (!m.analyzed || skip.contains(m.id) || m.id.equals(r.entryMethodId)) {
                    continue;
                }
                List<CallSiteInfo> in = r.callSites.stream()
                        .filter(s -> s.targetMethodId != null && m.id.equals(s.expansionTargetId())).toList();
                if (!in.isEmpty() && in.stream().noneMatch(s -> drawn(r, rules, skip, s))) {
                    skip.add(m.id);
                    changed = true;
                }
            }
        }
        return skip;
    }

    // ------------------------------------------------------------------ 바인딩 함수 · exclude

    /**
     * 바인딩 함수: 본문이 바인딩 코드·요청 읽기·다른 바인딩 함수 호출과 최상위 return 뿐인 source 메서드
     * (VO 를 채워 반환하는 toSearchVO, createCommand, 그 안에서 값만 만드는 parseParameter 등).
     * 상자를 따로 그리지 않고 호출한 쪽 바인딩 블록 안에 중첩해서 보인다 (본문 코드 전부 — 바인딩·return).
     * <ol>
     *   <li>순수 후보 P (최대 고정점): 블록은 최상위 return 뿐, 모든 호출이 바인딩 코드·요청 읽기·P 안의 메서드 호출</li>
     *   <li>P 중 바인딩이 있거나, 바인딩 함수를 부르거나, 들어오는 호출이 전부 바인딩 값 안(할당 호출 자체 제외)·
     *       다른 바인딩 함수 안인 것 (최소 고정점 — 값 함수 사슬 middle → inner, 재귀 selfRef 포함)</li>
     * </ol>
     */
    public static Set<String> bindingFunctions(AnalysisResult r) {
        Set<String> pure = new java.util.LinkedHashSet<>();
        for (MethodInfo m : r.methods()) {
            if (m.analyzed && m.origin == analyzer.model.Origin.SOURCE && !m.id.equals(r.entryMethodId)
                    && r.blocksOf(m.id).stream().allMatch(b -> b.type == BlockType.RETURN && b.parentBlockId == null)) {
                pure.add(m.id);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (java.util.Iterator<String> it = pure.iterator(); it.hasNext(); ) {
                String m = it.next();
                if (!r.callSitesOf(m).stream().allMatch(c -> FlowView.hidden(r, c) || readCall(r, c) || fnCall(r, pure, c))) {
                    it.remove();
                    changed = true;
                }
            }
        }
        Set<String> fns = new java.util.LinkedHashSet<>(FlowView.bindingMethods(r));
        changed = true;
        while (changed) {
            changed = false;
            for (String m : pure) {
                if (fns.contains(m)) {
                    continue;
                }
                boolean binds = r.bindings.stream().anyMatch(b -> b.methodId.equals(m));
                boolean nested = r.callSitesOf(m).stream().anyMatch(c -> fnCall(r, fns, c));
                List<CallSiteInfo> in = r.callSites.stream()
                        .filter(c -> c.targetMethodId != null && m.equals(c.expansionTargetId())).toList();
                boolean valueFn = !in.isEmpty() && in.stream().allMatch(c -> fns.contains(c.callerMethodId)
                        || r.bindings.stream().anyMatch(b -> b.methodId.equals(c.callerMethodId) && !c.id.equals(b.callSiteId)
                        && contains(b.location, c.location)));
                if (binds || nested || valueFn) {
                    fns.add(m);
                    changed = true;
                }
            }
        }
        return fns;
    }

    private static boolean fnCall(AnalysisResult r, Set<String> fns, CallSiteInfo c) {
        return c.targetMethodId != null && fns.contains(c.expansionTargetId())
                && (c.expansion == Expansion.EXPANDED || c.expansion == Expansion.SHARED || c.expansion == Expansion.CYCLE);
    }

    private static boolean readCall(AnalysisResult r, CallSiteInfo s) {
        return !r.parametersAt(s.id).isEmpty() && s.expansion != Expansion.EXPANDED
                && s.expansion != Expansion.SHARED && s.expansion != Expansion.CYCLE;
    }

    /** exclude 패턴에 맞는 호출 ({@link analyzer.serializer.Excludes}) */
    static boolean excludedCall(AnalysisResult r, List<String> patterns, CallSiteInfo s) {
        return analyzer.serializer.Excludes.call(r, patterns, s);
    }

    static boolean excludedMethod(AnalysisResult r, List<String> patterns, MethodInfo m) {
        return analyzer.serializer.Excludes.method(r, patterns, m);
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
            List<CallSiteInfo> stmt = st.element instanceof CallSiteInfo e0
                    ? statementGroup(e0, new ArrayList<>(st.group)) : null;
            if (stmt != null && stmt.size() == st.group.size()) {
                cur = statementNode(sl, stmt, new ArrayList<>(), cur);
            } else if (st.element instanceof CallSiteInfo) {
                // 한 문장의 호출 전부(안쪽 → 바깥, 예: a.b().c()) — 일반 흐름도와 같은 규칙
                for (CallSiteInfo s : st.group) {
                    if (FlowView.hidden(r, s) || argumentOnly(r, skipSet, s)) {
                        continue;
                    }
                    if (requestRead(s)) {
                        cur = flushReads(sl, new ArrayList<>(List.of(s)), cur);
                    } else {
                        callNode(sl, s);
                        cur = link(cur, s.id);
                    }
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

    /** 블록 노드의 한 줄: textClass = 글자 색(req·fn, 없으면 기본), objectId = 바인딩 줄이면 그 객체 */
    private record Line(String text, String textClass, String objectId, String bindingId) {
        Line(String text, String textClass, String objectId) {
            this(text, textClass, objectId, null);
        }
    }

    /**
     * 연속된 같은 종류의 요소를 블록 노드 1개로 합친다 (한 번에 한 종류만 쌓인다).
     * <ul>
     *   <li>bind — 바인딩: 요청 파라미터(글자색 req) · VO 값 설정 · 바인딩 함수(글자색 fn, 안쪽 바인딩 함수는 │ 로 중첩) ·
     *       값 조회 · 로직 호출 없는 선언문</li>
     *   <li>state — 상태 변화: 소스에 있는 변수 값 변경(=, 복합 대입, ++/--; V1 k/n)만. 메서드 호출은 넣지 않는다</li>
     *   <li>log — 로그: 로깅 호출을 간략히 (배경색으로 구분)</li>
     *   <li>code:&lt;css&gt; — 일반 호출: 세부 상자로 가지 않는 호출 코드 줄 (색 구분이 같은 것끼리, 하나뿐이면 예전 호출 노드)</li>
     * </ul>
     * 노드 ID = 첫 요소의 ID(호출 ID, 변수 변경이면 VAR_, 호출 없는 바인딩이면 SET_), 나머지 모델 ID 는 alias.
     */
    private final class Pending {
        final Subgraph sg;
        final MethodInfo m;
        final String container;
        String kind;
        String nodeId;
        final List<Line> lines = new ArrayList<>();
        final List<String> members = new ArrayList<>();
        /** 일반 호출 블록의 호출들 */
        final List<CallSiteInfo> codeCalls = new ArrayList<>();
        final Set<String> seenBindings = new java.util.HashSet<>();

        Pending(Subgraph sg, MethodInfo m, String container) {
            this.sg = sg;
            this.m = m;
            this.container = container;
        }

        /** kind 블록을 이어 쌓을 준비 — 다른 종류가 쌓여 있으면 먼저 내보낸다 */
        List<End> start(String k, List<End> cur, String firstId) {
            if (kind != null && !kind.equals(k)) {
                cur = flush(cur);
            }
            kind = k;
            if (nodeId == null) {
                nodeId = firstId;
            }
            return cur;
        }

        List<End> read(CallSiteInfo s, List<End> cur) {
            cur = start("bind", cur, s.id);
            for (var p : r.parametersAt(s.id)) {
                Line l = new Line(Labels.requestLine(r, s, p), "req", null);
                // 한 문장에 읽기가 여럿이면 문장 줄이 같다 — 한 번만
                String code = l.text().replaceFirst("^#\\d+ ", "");
                if (lines.stream().noneMatch(x -> "req".equals(x.textClass()) && x.text().replaceFirst("^#\\d+ ", "").equals(code))) {
                    lines.add(l);
                }
            }
            members.add(s.id);
            return cur;
        }

        /** 바인딩 코드(setter/builder/put, 그 인자 속 호출) — 이 호출에 걸린 바인딩 줄 */
        List<End> set(CallSiteInfo s, List<End> cur) {
            cur = start("bind", cur, s.id);
            boolean holder = !FlowView.hidden(r, s) && r.bindings.stream().noneMatch(b -> s.id.equals(b.callSiteId));
            for (Binding b : r.bindings) {
                if (b.methodId.equals(m.id) && java.util.Objects.equals(b.controlBlockId, container)
                        && (s.id.equals(b.callSiteId) || contains(s.location, b.location)) && seenBindings.add(b.id)) {
                    lines.add(new Line(Labels.bindingLine(r, b, true), null, b.objectId, b.id));
                }
            }
            if (holder) {
                // builder 끝 .build() 등: 문장 코드를 그 안의 바인딩 줄들 앞에 (안쪽 호출이 먼저 평가되어 줄이 먼저 쌓여 있다)
                int at = lines.size();
                String obj = null;
                for (int i = 0; i < lines.size(); i++) {
                    Line l = lines.get(i);
                    if (l.bindingId() != null && contains(s.location, r.binding(l.bindingId()).location)) {
                        at = i;
                        obj = l.objectId();
                        break;
                    }
                }
                lines.add(at, new Line(callCode(s), null, obj));
            }
            members.add(s.id);
            return cur;
        }

        /** 호출 없이 일어나는 바인딩 (생성자 인자가 리터럴뿐 등) */
        List<End> orphan(Binding b, List<End> cur) {
            cur = start("bind", cur, "SET_" + b.id);
            if (seenBindings.add(b.id)) {
                lines.add(new Line(Labels.bindingLine(r, b, true), null, b.objectId));
            }
            return cur;
        }

        List<End> function(CallSiteInfo s, List<End> cur) {
            cur = start("bind", cur, s.id);
            functionLines(s, "", true, new java.util.HashSet<>(), lines);
            members.add(s.id);
            return cur;
        }

        /** 값 조회: user = getLoginUser(request) — 본문을 분석하지 않은 source 메서드 결과를 변수에 받음 */
        List<End> fetch(CallSiteInfo s, List<End> cur) {
            cur = start("bind", cur, s.id);
            lines.add(new Line(callCode(s), null, null));
            members.add(s.id);
            assignedBy.getOrDefault(s.id, List.of()).forEach(c -> members.add(c.id));
            return cur;
        }

        /** 로직 호출 없는 변수 선언/대입 문장 한 줄 (이 문장의 변수 변경은 이력 표시로 흡수) */
        List<End> statement(List<CallSiteInfo> group, List<VariableChange> changes, List<End> cur) {
            CallSiteInfo first = group.get(0);
            CallSiteInfo last = group.get(group.size() - 1);
            cur = start("bind", cur, first.id);
            StringBuilder line = new StringBuilder("#" + first.order + (group.size() > 1 ? "–" + last.order : "") + " ")
                    .append(stripSemi(Labels.codeValue(r, first.callerMethodId, first.statementLocation, first.statement)));
            for (VariableChange c : r.variableChanges) {
                if (c.methodId.equals(first.callerMethodId) && contains(first.statementLocation, c.location)) {
                    line.append(" · ").append(Labels.historyTag(r, c));
                    changes.remove(c);
                    members.add(c.id);
                }
            }
            lines.add(new Line(line.toString(), null, null));
            group.forEach(c -> members.add(c.id));
            return cur;
        }

        List<End> log(CallSiteInfo s, List<End> cur) {
            cur = start("log", cur, s.id);
            lines.add(new Line(Labels.logLine(s), null, null));
            members.add(s.id);
            return cur;
        }

        /** 일반 호출 (세부 상자로 가지 않는 호출) — 색 구분(css)이 같으면 이어 쌓는다 */
        List<End> code(CallSiteInfo s, List<End> cur) {
            cur = start("code:" + callCss(s), cur, s.id);
            codeCalls.add(s);
            return cur;
        }

        List<End> change(VariableChange c, List<End> cur) {
            cur = start("state", cur, "VAR_" + c.id);
            lines.add(new Line(Labels.changeLine(r, c), null, null));
            members.add(c.id);
            return cur;
        }

        List<End> flush(List<End> cur) {
            String k = kind;
            String id = nodeId;
            kind = null;
            nodeId = null;
            if (k != null && k.startsWith("code:")) {
                List<CallSiteInfo> cs = new ArrayList<>(codeCalls);
                codeCalls.clear();
                if (cs.size() == 1) {
                    callNode(sg, cs.get(0));
                    return link(cur, id);
                }
                List<String> out = new ArrayList<>();
                for (CallSiteInfo c : cs) {
                    out.add(callCode(c));
                    String impl = Labels.implementationTarget(r, c);
                    if (impl != null) {
                        out.add("  impl 후보: " + impl);
                    }
                    String req = Labels.requestTag(r, c);
                    if (req != null) {
                        out.add("  " + req);
                    }
                }
                g.node(sg, id, out, Shape.RECT, k.substring(5));
                for (CallSiteInfo c : cs) {
                    if (!c.id.equals(id)) {
                        g.alias(c.id, id);
                    }
                    assignedBy.getOrDefault(c.id, List.of()).forEach(v -> g.alias(v.id, id));
                }
                return link(cur, id);
            }
            if (k == null || lines.isEmpty()) {
                lines.clear();
                members.clear();
                return cur;
            }
            List<String> out = new ArrayList<>();
            String css;
            switch (k) {
                case "bind" -> {
                    List<String> objects = lines.stream().map(Line::objectId).filter(java.util.Objects::nonNull).distinct().toList();
                    out.add("바인딩" + (objects.isEmpty() ? "" : " · " + String.join(", ",
                            objects.stream().map(o -> r.object(o).className).distinct().toList())));
                    String last = null;
                    for (Line l : lines) {
                        // 객체가 둘 이상이면 어느 객체의 줄인지 (객체가 바뀔 때마다)
                        if (objects.size() > 1 && l.objectId() != null && !l.objectId().equals(last)) {
                            var o = r.object(l.objectId());
                            out.add("· " + o.className + (o.variable == null ? "" : " " + o.variable));
                        }
                        if (l.objectId() != null) {
                            last = l.objectId();
                        }
                        out.add(l.textClass() == null ? l.text() : MermaidGraph.styled(l.textClass(), l.text()));
                    }
                    css = "bind";
                }
                case "state" -> {
                    out.add("상태 변화");
                    lines.forEach(l -> out.add(l.textClass() == null ? l.text() : MermaidGraph.styled(l.textClass(), l.text())));
                    css = "state";
                }
                default -> {
                    out.add("로그");
                    lines.forEach(l -> out.add(l.text()));
                    css = "log";
                }
            }
            g.node(sg, id, out, Shape.RECT, css);
            for (String x : members) {
                if (!x.equals(id) && !g.has(x)) {
                    g.alias(x, id);
                }
            }
            lines.clear();
            members.clear();
            return link(cur, id);
        }
    }

    /**
     * 바인딩 함수 호출 → 바인딩 블록 안의 줄들 (글자색 fn). 함수 본문(바인딩·요청 읽기·안쪽 바인딩 함수·return)을
     * 소스 순서대로 │ 들여쓰기로 중첩한다.
     */
    private void functionLines(CallSiteInfo s, String indent, boolean numbered, Set<String> visiting, List<Line> out) {
        String code = numbered ? callCode(s) : callCode(s).replaceFirst("^#\\d+ ", "");
        out.add(new Line(indent + "▸ " + code, "fn", null));
        String fid = s.expansionTargetId();
        String in = indent + "│ ";
        if (!visiting.add(fid)) {
            out.add(new Line(in + "↺ cycle", null, null));
            return;
        }
        List<Object> items = new ArrayList<>();
        r.bindings.stream().filter(b -> b.methodId.equals(fid)).forEach(items::add);
        for (CallSiteInfo c : r.callSitesOf(fid)) {
            if (fnCall(r, bindingFns, c) || readCall(r, c) && !FlowView.hidden(r, c)) {
                items.add(c);
            }
        }
        r.blocksOf(fid).stream().filter(b -> b.type == BlockType.RETURN).forEach(items::add);
        items.sort((x, y) -> compareLoc(itemLocation(x), itemLocation(y)));
        for (Object o : items) {
            if (o instanceof Binding b) {
                out.add(new Line(in + Labels.bindingLine(r, b, false), null, null));
            } else if (o instanceof CallSiteInfo c && fnCall(r, bindingFns, c)) {
                functionLines(c, in, false, visiting, out);
            } else if (o instanceof CallSiteInfo c) {
                r.parametersAt(c.id).forEach(p -> out.add(new Line(in + Labels.requestLine(r, c, p).replaceFirst("^#\\d+ ", ""), "req", null)));
            } else if (o instanceof ControlBlock b) {
                out.add(new Line(in + Labels.codeValue(r, b.methodId, b.location, Labels.blockHeader(b)), null, null));
            }
        }
        visiting.remove(fid);
    }

    private static SourceLocation itemLocation(Object o) {
        if (o instanceof Binding b) {
            return b.location;
        }
        if (o instanceof CallSiteInfo c) {
            return c.location;
        }
        return ((ControlBlock) o).location;
    }

    private static int compareLoc(SourceLocation a, SourceLocation b) {
        if (a == null || b == null) {
            return 0;
        }
        return a.beginLine() != b.beginLine() ? Integer.compare(a.beginLine(), b.beginLine())
                : Integer.compare(a.beginColumn(), b.beginColumn());
    }

    /**
     * 호출 코드 한 줄: #1 MemberVO member = checkLogin(request) · V1.
     * 결과를 변수에 바로 받지 않는 선언/대입 문장 안의 호출(int v = c ? compute() : 0)은 문장 전체를 보인다 (코드가 빠지지 않게).
     * 람다 본문 등 나중에 실행되는 호출은 ⟨deferred⟩.
     */
    private String callCode(CallSiteInfo s) {
        String var = s.resultUsage != null && s.resultUsage.startsWith("assigned:") ? s.resultUsage.substring(9) : null;
        StringBuilder code = new StringBuilder("#" + s.order + " ");
        if (var == null && s.statement != null && s.statementLocation != null) {
            code.append(stripSemi(Labels.codeValue(r, s.callerMethodId, s.statementLocation, s.statement).trim()));
        } else {
            if (var != null) {
                code.append(s.resultType == null ? "" : s.resultType + " ").append(var).append(" = ");
            }
            code.append(Labels.codeValue(r, s.callerMethodId, s.location, s.expression));
        }
        if (s.deferred) {
            code.append("  ⟨deferred⟩");
        }
        for (VariableChange c : assignedBy.getOrDefault(s.id, List.of())) {
            VariableInfo v = r.variable(c.variableId);
            code.append(v.name.equals(var) ? "" : " → " + v.name).append(" · ").append(Labels.historyTag(r, c));
        }
        return code.toString();
    }

    /**
     * 바인딩을 품은 호출 — builder 체인 끝 .build() 처럼 호출 위치 안에 같은 메서드·블록의 바인딩이 있는 호출
     * (본문을 그리는 메서드로 가는 호출·로직 역할 호출은 제외).
     */
    private boolean holdsBinding(MethodInfo m, String container, CallSiteInfo s) {
        if (leadsToDetail(s) || s.targetMethodId != null && logicRoles.contains(r.classOf(r.method(s.targetMethodId)).role)) {
            return false;
        }
        return r.bindings.stream().anyMatch(b -> b.methodId.equals(m.id) && java.util.Objects.equals(b.controlBlockId, container)
                && contains(s.location, b.location));
    }

    /** 바인딩의 할당 호출 그 자체 (setter·builder 메서드·put) */
    private boolean bindingCall(CallSiteInfo s) {
        return r.bindings.stream().anyMatch(b -> s.id.equals(b.callSiteId));
    }

    /** 바인딩 블록 안에 중첩해서 보일 바인딩 함수 호출 */
    private boolean bindingFnCall(CallSiteInfo s) {
        return fnCall(r, bindingFns, s);
    }

    private List<End> emit(Subgraph sg, MethodInfo m, ControlBlock parent, List<End> in) {
        Set<String> header = parent == null ? Set.of() : headerPositions(parent.type);
        String container = parent == null ? null : parent.id;
        List<End> cur = in;
        Pending p = new Pending(sg, m, container);
        List<Object> elems = Labels.elements(r, m.id, container, false);
        // 호출 없이 일어나는 바인딩(생성자 인자가 리터럴뿐 등)은 위치 순으로 바인딩 블록에 끼운다
        List<Binding> orphans = new ArrayList<>(r.bindings.stream().filter(b -> b.methodId.equals(m.id)
                && java.util.Objects.equals(b.controlBlockId, container) && b.callSiteId == null
                && elems.stream().noneMatch(o -> o instanceof CallSiteInfo s && FlowView.hidden(r, s)
                && contains(s.location, b.location))).toList());
        // 변수 값 변경: 식 안의 호출이 끝난 뒤(평가 순서). x = call() 은 호출 노드에 "→ x" 로 표시하므로 제외
        List<VariableChange> changes = new ArrayList<>(r.variableChanges.stream()
                .filter(c -> c.methodId.equals(m.id) && java.util.Objects.equals(c.controlBlockId, container))
                .filter(VariableChange::isChange) // 선언(타입 있는 초기화)은 값 변경이 아니다
                .filter(c -> assignedBy.values().stream().noneMatch(l -> l.contains(c)))
                .filter(c -> !supCalls.contains(c.id)).toList());
        java.util.Set<String> done = new java.util.HashSet<>(); // 문장 줄로 이미 그린 호출
        for (Object o : elems) {
            if (o instanceof CallSiteInfo cs && (done.contains(cs.id) || inLeaf.contains(cs.id))) {
                continue;
            }
            SourceLocation at = o instanceof CallSiteInfo s ? s.location : ((ControlBlock) o).location;
            while (!orphans.isEmpty() && before(orphans.get(0).location, at)) {
                cur = p.orphan(orphans.remove(0), cur);
            }
            while (!changes.isEmpty() && endsBefore(changes.get(0).location, at)) {
                cur = p.change(changes.remove(0), cur);
            }
            if (o instanceof CallSiteInfo s) {
                if (header.contains(s.position) || excludedCall(r, rules.excludes(), s)) {
                    continue;
                }
                if (bindingCall(s)) { // 바인딩 호출 자체(setter 등, 본문을 분석했어도) = 바인딩 줄
                    cur = p.set(s, cur);
                    continue;
                }
                if (bindingFnCall(s)) { // 바인딩 함수 = 바인딩 블록 안에 중첩
                    cur = p.function(s, cur);
                    continue;
                }
                List<CallSiteInfo> stmt = statementGroup(s, elems);
                if (stmt != null) { // 로직 호출 없는 변수 선언/대입 = 문장 통째로 바인딩 한 줄
                    cur = p.statement(stmt, changes, cur);
                    stmt.forEach(x -> done.add(x.id));
                    continue;
                }
                if (argumentOnly(r, skipSet, s)) { // 인자 안의 호출은 그리지 않음 (바깥 호출·문장이 대표)
                    continue;
                }
                if (FlowView.hidden(r, s) || holdsBinding(m, container, s)) { // 바인딩 코드(setter/builder/put·build(), 그 인자 속 호출)
                    cur = p.set(s, cur);
                } else if (requestRead(s)) {
                    cur = p.read(s, cur);
                } else if (LogCalls.isLog(s) && !leadsToDetail(s)) {
                    cur = p.log(s, cur);
                } else if (fetch(s) && s.resultUsage != null && s.resultUsage.startsWith("assigned:")) {
                    cur = p.fetch(s, cur);
                } else if (plainCall(s)) { // 일반 호출: 이어지는 것끼리 노드 1개 (기본 블록처럼)
                    cur = p.code(s, cur);
                } else {
                    cur = p.flush(cur);
                    callNode(sg, s);
                    cur = link(cur, s.id);
                }
            } else {
                cur = p.flush(cur);
                cur = block(sg, m, (ControlBlock) o, cur);
            }
        }
        for (Binding b : orphans) {
            cur = p.orphan(b, cur);
        }
        for (VariableChange c : changes) {
            cur = p.change(c, cur);
        }
        return p.flush(cur);
    }

    /** int size = XxxUtil.getInt(request, …) 처럼 선언 값이 요청 읽기 호출 그 자체 → 요청 파라미터 노드에 "· V1 1/n" 으로 표시됨 */
    private boolean initShownAsRead(VariableChange c) {
        return c.callSiteIds.stream().map(r::callSite)
                .anyMatch(x -> x.expression.equals(c.valueExpression) && requestRead(x) && !FlowView.hidden(r, x));
    }

    /**
     * s 가 속한 변수 선언/대입 문장의 호출들 (같은 컨테이너 안). 문장 노드로 그릴 조건이 아니면 null:
     * 로직 역할 호출·세부를 그리는 메서드로 가는 호출·바인딩 코드가 있거나, 요청 읽기뿐이거나,
     * 호출 하나가 문장 전체인 단순한 경우(x = call() — 호출 노드가 이미 같은 코드).
     */
    private List<CallSiteInfo> statementGroup(CallSiteInfo s, List<Object> elems) {
        if (s.statementLocation == null) {
            return null;
        }
        List<CallSiteInfo> group = elems.stream().filter(o -> o instanceof CallSiteInfo c
                        && s.statementLocation.equals(c.statementLocation)).map(o -> (CallSiteInfo) o).toList();
        for (CallSiteInfo c : group) {
            boolean logic = c.targetMethodId != null && (logicRoles.contains(r.classOf(r.method(c.targetMethodId)).role)
                    || c.implementationMethodId != null && logicRoles.contains(r.classOf(r.method(c.implementationMethodId)).role));
            boolean detailed = c.targetMethodId != null && drawnMethods.contains(c.expansionTargetId())
                    && (c.expansion == Expansion.EXPANDED || c.expansion == Expansion.SHARED || c.expansion == Expansion.CYCLE);
            if (logic || detailed || FlowView.hidden(r, c)) {
                return null;
            }
        }
        // 인자 안 호출(따로 그리지 않음)은 빼고 판단 — defVal(getParam(req, "x"), "0") 은 요청 읽기 하나
        List<CallSiteInfo> visible = group.stream().filter(c -> !argumentOnly(r, skipSet, c)).toList();
        if (visible.isEmpty() || visible.stream().allMatch(this::requestRead)) {
            return null;
        }
        if (visible.size() == 1) {
            CallSiteInfo c = visible.get(0);
            String var = c.resultUsage != null && c.resultUsage.startsWith("assigned:") ? c.resultUsage.substring(9) : null;
            String plain = (c.resultType == null ? "" : c.resultType + " ") + var + " = " + c.expression;
            if (var != null && stripSemi(c.statement).equals(plain)) {
                return null;
            }
        }
        return group;
    }

    private static String stripSemi(String s) {
        return s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
    }

    /** 문장 노드: #4–5 boolean flag = ODSUtil.isQAStore(shopID) && StringUtil.isNotEmpty(sdkVer) && "1".equals(pd) */
    private List<End> statementNode(Subgraph sg, List<CallSiteInfo> group, List<VariableChange> changes, List<End> cur) {
        CallSiteInfo first = group.get(0);
        CallSiteInfo last = group.get(group.size() - 1);
        StringBuilder line = new StringBuilder("#" + first.order + (group.size() > 1 ? "–" + last.order : "") + " ")
                .append(stripSemi(Labels.codeValue(r, first.callerMethodId, first.statementLocation, first.statement)));
        // 이 문장이 추적 변수의 선언/변경이면 이력 표시, 값 변경 노드는 따로 만들지 않음
        for (VariableChange c : r.variableChanges) {
            if (c.methodId.equals(first.callerMethodId) && contains(first.statementLocation, c.location)) {
                line.append(" · ").append(Labels.historyTag(r, c));
                changes.remove(c);
                g.alias(c.id, first.id);
            }
        }
        g.node(sg, first.id, List.of(line.toString()), Shape.RECT, "bind");
        group.stream().skip(1).forEach(c -> g.alias(c.id, first.id));
        return link(cur, first.id);
    }

    /** 연속된 변수 값 변경 → "값 변경" 노드 1개 (ID = VAR_첫변경ID, 나머지 변경 ID 는 alias) */
    private List<End> flushVars(Subgraph sg, List<VariableChange> vars, List<End> cur) {
        if (vars.isEmpty()) {
            return cur;
        }
        List<String> lines = new ArrayList<>(List.of("상태 변화"));
        vars.forEach(c -> lines.add(Labels.changeLine(r, c)));
        String id = "VAR_" + vars.get(0).id;
        g.node(sg, id, lines, Shape.RECT, "state");
        vars.forEach(c -> g.alias(c.id, id));
        vars.clear();
        return link(cur, id);
    }

    /** a 가 끝난 뒤에 b 가 시작하는지 */
    private static boolean endsBefore(SourceLocation a, SourceLocation b) {
        return a != null && b != null && (a.endLine() < b.beginLine()
                || a.endLine() == b.beginLine() && a.endColumn() < b.beginColumn());
    }

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
                // 반복문 = Loop 영역(subgraph) 하나: 조건 노드 + 본문, 본문 끝에서 조건으로 되돌아간다
                Subgraph ls = g.subgraph(sg, "sg_" + b.id, "Loop");
                if (b.type == BlockType.DO_WHILE) {
                    // do-while: 본문을 먼저 지나고 조건 → loop 이면 본문 첫 노드로, done 이면 밖으로
                    Set<String> before = new java.util.LinkedHashSet<>(g.nodeIds());
                    List<End> body = emit(ls, m, b, in);
                    String first = g.nodeIds().stream().filter(id -> !before.contains(id)).findFirst().orElse(null);
                    decision(ls, b, body);
                    // 본문이 비어 있으면 조건(조건식 안 호출 노드가 있으면 그 첫 노드)부터 다시
                    g.edge(b.id, first == null ? loopReentry(b) : first, "loop", EdgeStyle.FLOW);
                    return List.of(new End(b.id, "done", EdgeStyle.FLOW));
                }
                decision(ls, b, in);
                List<End> back = emit(ls, m, b, List.of(new End(b.id, "loop", EdgeStyle.FLOW)));
                String reentry = loopReentry(b);
                for (End e : back) {
                    g.edge(e.node(), reentry, e.label() == null ? "next" : e.label(), e.style());
                }
                return List.of(new End(b.id, "done", EdgeStyle.FLOW));
            }
            case TRY -> {
                return phases == null ? tryFull(sg, m, b, in) : tryBlock(sg, m, b, in);
            }
            case CATCH, FINALLY -> {
                return in;
            }
            case RETURN, THROW -> {
                // return/throw 식 안의 호출은 리프 노드에 이미 보이므로 따로 그리지 않는다 (세부 박스로 가는 호출만 남김)
                List<String> folded = new ArrayList<>();
                for (Object o : Labels.elements(r, m.id, b.id, false)) {
                    if (o instanceof CallSiteInfo c && !leadsToDetail(c)) {
                        folded.add(c.id);
                    }
                }
                inLeaf.addAll(folded);
                List<End> p = emit(sg, m, b, in);
                leaf(sg, m, b);
                folded.forEach(id -> g.alias(id, b.id));
                link(p, b.id);
                return List.of();
            }
            default -> {
                return emit(sg, m, b, in);
            }
        }
    }

    /** 헤더 위치 호출(조건식 안 호출 등) → 조건 노드 */
    /**
     * 조건 노드(◇). 조건식 안의 호출은 조건 텍스트에 이미 보이므로 따로 그리지 않는다(중복 방지) — 단, 세부 박스로
     * 이어지는 호출(Service·DAO 등 그리는 메서드)은 ==> 간선을 위해 노드로 남긴다. 조건식 안 요청 읽기는 request.이름 으로.
     */
    private void decision(Subgraph sg, ControlBlock b, List<End> in) {
        List<End> cur = in;
        List<String> inCondition = new ArrayList<>();
        for (String cid : b.callIds) {
            CallSiteInfo s = r.callSite(cid);
            if (headerPositions(b.type).contains(s.position) && !FlowView.hidden(r, s)) {
                boolean detailed = s.targetMethodId != null && drawnMethods.contains(s.expansionTargetId())
                        && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED || s.expansion == Expansion.CYCLE);
                if (detailed) {
                    callNode(sg, s);
                    cur = link(cur, s.id);
                } else {
                    inCondition.add(s.id);
                }
            }
        }
        g.node(sg, b.id, headerLines(b), Shape.DIAMOND, null);
        inCondition.forEach(id -> g.alias(id, b.id)); // 조건 노드에 포함
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
        int before = g.nodeIds().size();
        List<End> body = emit(ts, m, t, in);
        if (g.nodeIds().size() == before) {
            // 본문에 그릴 노드가 없음(외부 호출뿐, 예: Long.parseLong(x)) — 본문 코드를 노드 하나로, 흐름이 try 를 지나가게
            String id = "BODY_" + t.id;
            g.node(ts, id, List.of(t.bodyText == null || t.bodyText.isBlank() ? "(빈 try)" : t.bodyText), Shape.RECT, "codeline");
            body = link(in, id);
        }
        List<End> out = new ArrayList<>(body);
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
                    .forEach(x -> lines.add(x.expression));
            g.node(sg, f.id, lines, Shape.RECT, "fin");
            return link(out, f.id);
        }
        return out;
    }

    /**
     * 반복할 때 다시 들어가는 노드: 조건식을 매번 평가하므로 조건식 안 호출이 노드로 그려졌으면(본문을 그리는 메서드) 그 첫 노드,
     * 아니면 조건 노드. for 의 초기화식·for-each 의 대상 식은 한 번만 평가되므로 제외.
     */
    private String loopReentry(ControlBlock b) {
        for (String cid : b.callIds) {
            CallSiteInfo s = r.callSite(cid);
            if ("CONDITION".equals(s.position) && g.nodeIds().contains(s.id)) {
                return s.id;
            }
        }
        return b.id;
    }

    /**
     * 일반 흐름도의 try: 본문은 try 블록(subgraph) 안 정상 흐름, catch 마다 노드 + 그 본문 전부, finally 노드 + 본문.
     * 블록 → catch 는 예외 간선(타입), 잡히는 throw → catch, 빠져나가는 return/throw → finally 도 점선으로 잇는다.
     */
    private List<End> tryFull(Subgraph sg, MethodInfo m, ControlBlock t, List<End> in) {
        Subgraph ts = g.subgraph(sg, "sg_" + t.id, Labels.blockHeader(t));
        int before = g.nodeIds().size();
        List<End> body = emit(ts, m, t, in);
        if (g.nodeIds().size() == before) {
            String id = "BODY_" + t.id;
            g.node(ts, id, List.of(t.bodyText == null || t.bodyText.isBlank() ? "(빈 try)" : t.bodyText), Shape.RECT, "codeline");
            body = link(in, id);
        }
        List<End> out = new ArrayList<>(body);
        for (String cid : t.catchBlockIds) {
            ControlBlock c = r.block(cid);
            g.node(sg, c.id, List.of(Labels.blockHeader(c)), Shape.SUBROUTINE, "exc");
            g.edge(ts.id(), c.id, String.join(" | ", c.exceptionTypes), EdgeStyle.EXCEPTION);
            out.addAll(emit(sg, m, c, List.of(new End(c.id))));
        }
        if (t.finallyBlockId != null) {
            ControlBlock f = r.block(t.finallyBlockId);
            g.node(sg, f.id, List.of("finally"), Shape.RECT, "fin");
            List<End> into = link(out, f.id);
            return emit(sg, m, f, into);
        }
        return out;
    }

    private final Map<String, Subgraph> methodParents = new HashMap<>();

    private void leaf(Subgraph sg, MethodInfo m, ControlBlock b) {
        List<String> lines = new ArrayList<>(List.of(Labels.codeValue(r, b.methodId, b.location, Labels.blockHeader(b))));
        if (b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED) {
            lines.add("(catch 경로 unresolved)");
        }
        g.node(sg, b.id, lines, b.type == BlockType.RETURN ? Shape.STADIUM : Shape.ASYM,
                b.type == BlockType.RETURN ? "ret" : "throw");
        if (phases == null) {
            // 일반 흐름도: 잡히는 throw → 그 catch, 빠져나가며 지나는 finally 를 점선으로 (catch/finally 를 모두 그리므로)
            if (b.type == BlockType.THROW && b.caughtBy != null) {
                g.edge(b.id, b.caughtBy, "catch", EdgeStyle.EXCEPTION);
            }
            b.executesFinally.forEach(f -> g.edge(b.id, f, "finally", EdgeStyle.EXCEPTION));
        }
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

    /**
     * 호출 노드 = 코드 한 줄(줄이지 않음): #1 MemberVO member = checkLogin(request) · V1 1/3.
     * 역할·접근제어자·Depth·depth-limit 같은 꼬리표는 쓰지 않는다 (색으로만 구분). 구현 후보는 따로 한 줄.
     */
    /** 일반 호출 블록으로 합칠 호출: 세부 상자로 가지 않고, 접힌 보조 메서드·값 조회가 아닌 호출 */
    private boolean plainCall(CallSiteInfo s) {
        if (leadsToDetail(s) || s.targetMethodId != null && collapsed.contains(s.expansionTargetId())) {
            return false;
        }
        return !(fetch(s) && s.resultUsage != null && s.resultUsage.startsWith("assigned:"));
    }

    /** 호출 노드 색 구분 (해석·전개 상태) */
    private String callCss(CallSiteInfo s) {
        boolean req = Labels.requestTag(r, s) != null;
        return switch (s.expansion) {
            case NOT_APPLICABLE -> "unresolved";
            case CYCLE -> "cycle";
            case DEPTH_LIMIT -> req ? "req" : "depth";
            case EXTERNAL, NO_BODY -> req ? "req" : s.resolution == ResolutionStatus.RESOLVED ? "external" : "unresolved";
            default -> req ? "req" : s.resolution == ResolutionStatus.RESOLVED ? "codeline" : "unresolved";
        };
    }

    private void callNode(Subgraph sg, CallSiteInfo s) {
        List<String> lines = new ArrayList<>();
        boolean folded = s.targetMethodId != null && collapsed.contains(s.expansionTargetId());
        String var = s.resultUsage != null && s.resultUsage.startsWith("assigned:") ? s.resultUsage.substring(9) : null;
        String code = callCode(s);
        lines.add(code);
        if (var != null && fetch(s)) {
            // 값 조회는 바인딩으로 끝: 내부로 들어가지 않고 코드 한 줄
            g.node(sg, s.id, lines, Shape.RECT, "bind");
            assignedBy.getOrDefault(s.id, List.of()).forEach(c -> g.alias(c.id, s.id));
            return;
        }
        if (folded) {
            // 내부 함수(Service·DAO 로 가지 않는 보조 메서드)는 세부 없이 코드 한 줄 (+ 안에서 채우는 VO)
            List<String> vos = helperObjects(s.expansionTargetId());
            if (!vos.isEmpty()) {
                lines.add("VO 값 설정 · " + String.join(", ", vos));
            }
            g.node(sg, s.id, lines, Shape.RECT, vos.isEmpty() ? "codeline" : "bind");
            assignedBy.getOrDefault(s.id, List.of()).forEach(c -> g.alias(c.id, s.id));
            return;
        }
        String impl = Labels.implementationTarget(r, s);
        if (impl != null) {
            lines.add("impl 후보: " + impl);
        }
        String req = Labels.requestTag(r, s);
        if (req != null) {
            lines.add(req);
        }
        g.node(sg, s.id, lines, Shape.RECT, callCss(s));
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

    private List<String> headerLines(ControlBlock b) {
        List<String> l = new ArrayList<>(Labels.blockHeaderLines(r, b));
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
