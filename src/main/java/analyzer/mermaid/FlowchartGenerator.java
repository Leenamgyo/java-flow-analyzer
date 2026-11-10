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
    public static final Map<String, String> DARK_CLASS_DEFS = new LinkedHashMap<>();
    public static final Map<String, String> DARK_PHASE_CLASS_DEFS = new LinkedHashMap<>();

    /**
     * 코드 상자(네모)는 색으로 구분하지 않는다 (사용자 요구) — 호출·요청·VO·상태 변화·로그 모두 같은 상자,
     * 구분은 글자 색(요청 값 청록·바인딩 함수 보라·상태 변화 주황)만. 모양이 다른 노드(분기·return·throw·catch)는 그대로.
     */
    private static final String BOX = "fill:#f8fafc,stroke:#64748b";
    private static final String DARK_BOX = "fill:#1e293b,stroke:#94a3b8,color:#e2e8f0";

    static {
        CLASS_DEFS.put("entry", "fill:#dbeafe,stroke:#1d4ed8,stroke-width:2px");
        CLASS_DEFS.put("method", "fill:#eef2ff,stroke:#4338ca");
        CLASS_DEFS.put("external", BOX);
        CLASS_DEFS.put("depth", BOX);
        CLASS_DEFS.put("unresolved", BOX);
        CLASS_DEFS.put("cycle", BOX);
        CLASS_DEFS.put("ret", "fill:#dcfce7,stroke:#15803d");
        CLASS_DEFS.put("throw", "fill:#fee2e2,stroke:#b91c1c");
        CLASS_DEFS.put("exc", "fill:#fee2e2,stroke:#b91c1c,stroke-dasharray:4 3");
        CLASS_DEFS.put("req", BOX);
        CLASS_DEFS.put("bind", BOX);
        CLASS_DEFS.put("var", BOX);
        CLASS_DEFS.put("golink", "fill:#f8fafc,stroke:#64748b,stroke-dasharray:5 3");
        CLASS_DEFS.put("codeline", BOX);
        CLASS_DEFS.put("fin", "fill:#f1f5f9,stroke:#475569,stroke-dasharray:3 3");
        CLASS_DEFS.put("state", BOX);
        CLASS_DEFS.put("log", BOX);
        CLASS_DEFS.put("loopnext", "fill:#eff6ff,stroke:#2563eb,color:#2563eb");
        // 세부 로직이 있는 호출 (본문을 그린 메서드로 가는 호출) — 선(==>) 대신 상자 색 (사용자 요구)
        CLASS_DEFS.put("detail", "fill:#fef3c7,stroke:#b45309,stroke-width:2px");
        PHASE_CLASS_DEFS.putAll(CLASS_DEFS);
        PHASE_CLASS_DEFS.put("check", "fill:#fefce8,stroke:#a16207");
        PHASE_CLASS_DEFS.put("post", "fill:#f5f3ff,stroke:#6d28d9");
        // 다크 테마: 어두운 바탕 + 밝은 테두리·글자 (같은 계열 색, 클래스 이름은 라이트와 같다)
        DARK_CLASS_DEFS.put("entry", "fill:#1e3a8a,stroke:#60a5fa,stroke-width:2px,color:#f8fafc");
        DARK_CLASS_DEFS.put("method", "fill:#1e1b4b,stroke:#818cf8,color:#e0e7ff");
        DARK_CLASS_DEFS.put("external", DARK_BOX);
        DARK_CLASS_DEFS.put("depth", DARK_BOX);
        DARK_CLASS_DEFS.put("unresolved", DARK_BOX);
        DARK_CLASS_DEFS.put("cycle", DARK_BOX);
        DARK_CLASS_DEFS.put("ret", "fill:#052e16,stroke:#4ade80,color:#dcfce7");
        DARK_CLASS_DEFS.put("throw", "fill:#450a0a,stroke:#f87171,color:#fee2e2");
        DARK_CLASS_DEFS.put("exc", "fill:#450a0a,stroke:#f87171,color:#fee2e2,stroke-dasharray:4 3");
        DARK_CLASS_DEFS.put("req", DARK_BOX);
        DARK_CLASS_DEFS.put("bind", DARK_BOX);
        DARK_CLASS_DEFS.put("var", DARK_BOX);
        DARK_CLASS_DEFS.put("golink", "fill:#0f172a,stroke:#94a3b8,color:#e2e8f0,stroke-dasharray:5 3");
        DARK_CLASS_DEFS.put("codeline", DARK_BOX);
        DARK_CLASS_DEFS.put("fin", "fill:#1e293b,stroke:#94a3b8,color:#e2e8f0,stroke-dasharray:3 3");
        DARK_CLASS_DEFS.put("state", DARK_BOX);
        DARK_CLASS_DEFS.put("log", DARK_BOX);
        DARK_CLASS_DEFS.put("loopnext", "fill:#172554,stroke:#60a5fa,color:#bfdbfe");
        DARK_CLASS_DEFS.put("detail", "fill:#451a03,stroke:#f59e0b,stroke-width:2px,color:#fef3c7");
        DARK_PHASE_CLASS_DEFS.putAll(DARK_CLASS_DEFS);
        DARK_PHASE_CLASS_DEFS.put("check", "fill:#422006,stroke:#eab308,color:#fef9c3");
        DARK_PHASE_CLASS_DEFS.put("post", "fill:#2e1065,stroke:#a78bfa,color:#ede9fe");
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

    /** 반복문 표시 (if 와 구분 — 조건 노드 육각형과 함께) */
    /**
     * 반복 표시 — "반복" 글자 없이 (사용자: 반복 글자가 어색할 때가 있어 빼고 색으로 구분). 반복 안 간선(본문으로 들어가는 선·
     * 되돌아가는 선 ↻)은 MermaidGraph.LOOP_MARK 로 표시해 반복 색(linkStyle)으로, 빠져나가는 선은 일반 선.
     */
    static final String LOOP_TITLE = "↻";
    static final String LOOP_ENTER = MermaidGraph.LOOP_MARK;
    static final String LOOP_NEXT = MermaidGraph.LOOP_MARK + "↻";
    static final String LOOP_AGAIN = MermaidGraph.LOOP_MARK + "↻";
    static final String LOOP_DONE = null;

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
            Subgraph ms = g.subgraph(parent, "sg_" + m.id, Labels.methodName(r, m) + "() «" + r.classOf(m).role.label() + "»");
            methodGraphs.put(m.id, ms);
            List<String> lines = new ArrayList<>(List.of(Labels.methodSignature(r, m), m.visibility.text() + " · D" + m.depth));
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
        return checked(result).render(direction, layout, classDefs());
    }

    /**
     * 검증을 마친 흐름도 그래프 (렌더링 형식과 무관한 중간 표현) — Mermaid 문법(render)·HTML 뷰어(HtmlFlowchart) 공용.
     * 검증(그래프 구조 + 모델 대응 check) 실패 시 예외.
     */
    public MermaidGraph checked(AnalysisResult result) {
        MermaidGraph graph = build(result);
        List<String> errors = new ArrayList<>(graph.validate());
        errors.addAll(check(result, graph, rules));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        return graph.maxLineLength(maxLineLength).theme(theme);
    }

    /** 이 생성기의 class 색 정의 (테마·단계 보기에 맞는 것) */
    public Map<String, String> classDefinitions() {
        return classDefs();
    }

    /** 색 테마 (설정 output.color-theme): light(기본, 흰 바탕) | dark */
    private String theme = "light";

    public FlowchartGenerator theme(String t) {
        this.theme = MermaidGraph.checkTheme(t);
        return this;
    }

    private Map<String, String> classDefs() {
        boolean dark = "dark".equals(theme);
        return phases == null ? (dark ? DARK_CLASS_DEFS : CLASS_DEFS) : (dark ? DARK_PHASE_CLASS_DEFS : PHASE_CLASS_DEFS);
    }

    private int maxLineLength = 100;
    /** 노드 하나의 최대 줄 수 (output.max-node-lines) — 넘으면 다음 노드로 이어서 */
    private int maxNodeLines = 30;

    /** 골격(skeleton) 보기도 만들지 — false 면 구간 파일에 ⌂ 골격 링크도 없음 */
    private boolean skeleton = true;

    public FlowchartGenerator skeleton(boolean on) {
        this.skeleton = on;
        return this;
    }

    public FlowchartGenerator maxNodeLines(int n) {
        this.maxNodeLines = n;
        return this;
    }

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

    /** 00-overview: 메서드 노드 + 메서드 간 호출(==>) 집계. 노드를 누르면 그 메서드 골격 SVG 로 */
    private MermaidGraph overview(MermaidGraph full, List<MethodInfo> drawn, Map<String, String> owner, Map<String, String> base) {
        MermaidGraph ov = new MermaidGraph().maxLineLength(maxLineLength).showCallEdges(true); // 메서드 호출 관계는 선으로
        for (MethodInfo m : drawn) {
            ov.node(ov.root(), m.id, List.of(m.id + " " + Labels.methodName(r, m) + "()",
                    "«" + r.classOf(m).role.label() + "» · " + base.get(m.id) + "-skeleton"), Shape.STADIUM,
                    m.id.equals(r.entryMethodId) ? "entry" : "method");
            ov.link(m.id, base.get(m.id) + "-skeleton.svg", "골격");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var e : full.edges()) {
            if (e.style() == EdgeStyle.CALL && owner.containsKey(e.from()) && base.containsKey(e.to())) {
                String from = owner.get(e.from());
                if (!from.equals(e.to()) && seen.add(from + ">" + e.to())) {
                    ov.edge(from, e.to(), e.label(), EdgeStyle.CALL);
                }
            }
        }
        return ov;
    }

    /** 긴 메서드 보기: 구간 분할(A)과 골격(B). 둘 다 일반 흐름도와 같은 그래프에서 떼어 낸다 */
    public record Sectioned(List<Part> sections, List<Part> skeletons) {
    }

    /**
     * (A) 구간 분할: 메서드마다 그린 순서대로 노드를 묶어 구간 하나가 라벨 줄 size 줄 남짓이 되게 나눈다.
     *     try·Loop 블록이 size 이하면 통째로 한 구간에, 넘으면 그 안에서 나눈다(블록 상자는 구간마다 유지).
     *     다음 구간으로 넘어가는 간선은 "▶ 구간 k" 안내 노드, 이전 구간에서 들어오는 간선은 "◀ 구간 k" 안내 노드.
     * (B) 골격: 메서드마다 1장 — 분기(◇)·return/throw·catch/finally·세부 메서드로 가는 호출(==>)만 남기고
     *     그 사이 코드 노드 사슬은 코드 줄을 모두 담은 노드 하나로 합친다(줄을 생략하지 않음, 클릭 → A 의 그 구간 파일).
     * 내용을 빼지 않는다 — 구간 파일들을 합치면 메서드 흐름도와 같은 노드·간선이다.
     */
    public Sectioned renderSectioned(AnalysisResult result, String direction, String layout, int size) {
        MermaidGraph full = build(result);
        List<String> errors = new ArrayList<>(full.validate());
        errors.addAll(check(result, full, rules));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Mermaid 검증 실패:\n" + String.join("\n", errors));
        }
        Map<String, String> classDefs = classDefs();
        List<MethodInfo> drawn = r.methods().stream().filter(m -> drawnMethods.contains(m.id) && full.has("sg_" + m.id)).toList();
        Map<String, String> owner = new HashMap<>();
        for (MethodInfo m : drawn) {
            full.idsUnder("sg_" + m.id).forEach(id -> owner.putIfAbsent(id, m.id));
        }
        // 구간 나누기: 노드 → (메서드, 구간 번호)
        Map<String, List<Set<String>>> sectionsOf = new LinkedHashMap<>();
        Map<String, Integer> sectionNo = new HashMap<>();
        for (MethodInfo m : drawn) {
            List<Set<String>> secs = pack(full, units(full, "sg_" + m.id, size), size);
            sectionsOf.put(m.id, secs);
            for (int k = 0; k < secs.size(); k++) {
                for (String id : secs.get(k)) {
                    sectionNo.put(id, k + 1);
                }
            }
            // 여러 구간에 걸친 블록(subgraph) ID 는 첫 노드의 구간 (블록에서 나가는 예외 간선의 안내용)
            for (String id : full.idsUnder("sg_" + m.id)) {
                if (!sectionNo.containsKey(id)) {
                    full.idsUnder(id).stream().filter(sectionNo::containsKey).findFirst()
                            .ifPresent(first -> sectionNo.put(id, sectionNo.get(first)));
                }
            }
        }
        Map<String, String> base = new LinkedHashMap<>();
        int i = 1;
        for (MethodInfo m : drawn) {
            base.put(m.id, String.format("%02d-%s-%s", i++, m.id, Labels.methodName(r, m)));
        }
        // 노드가 든 구간 파일 이름 (확장자 없음) — 링크·안내 공용
        java.util.function.Function<String, String> sectionFile = id -> {
            String m = owner.get(id);
            if (m == null) {
                return null;
            }
            return base.get(m) + (sectionsOf.get(m).size() > 1 ? "-s" + sectionNo.getOrDefault(id, 1) : "");
        };
        java.util.function.Function<String, String> where = id -> {
            String m = owner.get(id);
            if (m == null) {
                return id;
            }
            int n = sectionsOf.get(m).size();
            return m + (n > 1 ? " 구간 " + sectionNo.getOrDefault(id, 1) + "/" + n : "") + " — " + sectionFile.apply(id) + ".mmd";
        };
        List<Part> sections = new ArrayList<>();
        for (MethodInfo m : drawn) {
            List<Set<String>> secs = sectionsOf.get(m.id);
            for (int k = 0; k < secs.size(); k++) {
                int no = k + 1;
                MermaidGraph part = full.extractNodes(secs.get(k),
                        to -> List.of("▶ " + full.labelOf(to), where.apply(to)),
                        from -> m.id.equals(owner.get(from)) ? List.of("◀ 구간 " + sectionNo.get(from) + " 에서", full.labelOf(from)) : null);
                // 클릭 이동 (SVG): ▶/◀ 안내 → 그 구간 파일, ⌂ → 이 메서드 골격
                for (String id : part.nodeIds()) {
                    String target = id.startsWith("go_") ? id.substring(3) : id.startsWith("from_") ? id.substring(5) : null;
                    String f = target == null ? null : sectionFile.apply(target);
                    if (f != null) {
                        part.link(id, f + ".svg", f);
                    }
                }
                // 세부 호출 노드 → 그 메서드 흐름도 (선 대신 클릭 이동)
                for (var e : full.edges()) {
                    if (e.style() == EdgeStyle.CALL && part.has(e.from()) && part.nodeById(e.from()) != null && base.containsKey(e.to())) {
                        part.link(e.from(), base.get(e.to()) + (sectionsOf.get(e.to()).size() > 1 ? "-s1" : "") + ".svg", "세부");
                    }
                }
                if (skeleton) {
                part.node(part.root(), "nav_skeleton", List.of("⌂ 골격 · " + m.id + " " + Labels.methodName(r, m) + "()"
                        + (secs.size() > 1 ? " · 구간 " + no + "/" + secs.size() : "")), Shape.SUBROUTINE, "golink");
                part.link("nav_skeleton", "../skeleton/" + base.get(m.id) + "-skeleton.svg", "골격");
                }
                String file = base.get(m.id) + (secs.size() > 1 ? "-s" + no : "") + ".mmd";
                String title = m.id + " " + Labels.methodName(r, m) + "()" + (secs.size() > 1 ? " — 구간 " + no + "/" + secs.size() : "");
                sections.add(new Part(file, title, part.theme(theme).render(direction, layout, classDefs)));
            }
        }
        List<Part> skeletons = new ArrayList<>();
        if (!skeleton) {
            return new Sectioned(sections, skeletons);
        }
        skeletons.add(new Part("00-overview.mmd", "전체 구조 (메서드 호출 관계)",
                overview(full, drawn, owner, base).theme(theme).render(direction, layout, classDefs)));
        for (MethodInfo m : drawn) {
            MermaidGraph one = full.extract("sg_" + m.id, to -> List.of("▶ " + full.labelOf(to), where.apply(to)));
            Set<String> calls = new java.util.HashSet<>();
            full.edges().stream().filter(e -> e.style() == EdgeStyle.CALL).forEach(e -> calls.add(e.from()));
            int n = sectionsOf.get(m.id).size();
            Set<String> folded = new java.util.HashSet<>();
            MermaidGraph sk = one.collapse(
                    node -> node.shape() != Shape.RECT || calls.contains(node.id())
                            || List.of("ret", "throw", "fin", "exc").contains(node.cssClass()),
                    chain -> {
                        folded.add(chain.get(0).id());
                        return skeletonLabel(chain, n > 1 ? sectionNo.get(chain.get(0).id()) : 0);
                    },
                    "codeline", 2, maxNodeLines);
            // 클릭 이동 (SVG): 접은 노드 → 상세 구간, ▶ 다른 메서드 → 그 메서드 골격, ⌂ → 전체 구조
            for (String id : sk.nodeIds()) {
                if (folded.contains(id)) {
                    sk.link(id, "../sections/" + sectionFile.apply(id) + ".svg", "상세");
                } else if (id.startsWith("go_") && owner.get(id.substring(3)) != null) {
                    sk.link(id, base.get(owner.get(id.substring(3))) + "-skeleton.svg", "골격");
                }
            }
            for (var e : full.edges()) {
                if (e.style() == EdgeStyle.CALL && sk.nodeById(e.from()) != null && base.containsKey(e.to()) && !folded.contains(e.from())) {
                    sk.link(e.from(), base.get(e.to()) + "-skeleton.svg", "세부");
                }
            }
            sk.node(sk.root(), "nav_overview", List.of("⌂ 전체 구조 (메서드 호출 관계)"), Shape.SUBROUTINE, "golink");
            sk.link("nav_overview", "00-overview.svg", "전체 구조");
            skeletons.add(new Part(base.get(m.id) + "-skeleton.mmd", m.id + " " + Labels.methodName(r, m) + "() — 골격",
                    sk.theme(theme).render(direction, layout, classDefs)));
        }
        return new Sectioned(sections, skeletons);
    }

    /**
     * 골격의 합친 노드 라벨: 사슬의 코드 줄 전부 (생략 없음 — 사용자 요구: "⋯ 코드 n줄" 처럼 코드를 숨기지 않는다).
     * 분기·return/throw·세부 호출 사이의 코드 노드 여러 개를 노드 하나로 합칠 뿐이다. 클릭하면 상세 구간으로 간다.
     */
    private static List<String> skeletonLabel(List<MermaidGraph.Node> chain, int section) {
        List<String> out = new ArrayList<>();
        for (MermaidGraph.Node n : chain) {
            out.addAll(n.lines());
        }
        return out;
    }

    /** 노드 무게 = 라벨 줄 수 (최소 1), subgraph 는 안쪽 합 */
    private static int weight(MermaidGraph g, String id) {
        if (g.isSubgraph(id)) {
            int w = 0;
            for (String c : g.childrenOf(id)) {
                w += weight(g, c);
            }
            return w;
        }
        MermaidGraph.Node n = g.nodeById(id);
        return n == null ? 0 : Math.max(1, n.lines().size());
    }

    /** 구간 단위: 바로 아래 노드 하나 / size 이하 블록 통째 / 넘는 블록은 안쪽 단위로 */
    private static List<Set<String>> units(MermaidGraph g, String sg, int size) {
        List<Set<String>> out = new ArrayList<>();
        for (String c : g.childrenOf(sg)) {
            if (!g.isSubgraph(c)) {
                out.add(Set.of(c));
            } else if (weight(g, c) <= size) {
                out.add(g.idsUnder(c));
            } else {
                out.addAll(units(g, c, size));
            }
        }
        return out;
    }

    /** 앞에서부터 size 를 넘지 않게 단위를 묶는다 (단위 하나가 size 를 넘으면 그 단위만 한 구간) */
    private static List<Set<String>> pack(MermaidGraph g, List<Set<String>> units, int size) {
        List<Set<String>> out = new ArrayList<>();
        Set<String> cur = new java.util.LinkedHashSet<>();
        int w = 0;
        for (Set<String> u : units) {
            int uw = 0;
            for (String id : u) {
                if (!g.isSubgraph(id)) {
                    uw += weight(g, id);
                }
            }
            if (!cur.isEmpty() && w + uw > size) {
                out.add(cur);
                cur = new java.util.LinkedHashSet<>();
                w = 0;
            }
            cur.addAll(u);
            w += uw;
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    /** 분할 파일을 한 문서로: 목차 + 파트마다 ```mermaid 블록 */
    public static String combinedMarkdown(String title, List<Part> parts) {
        return combinedMarkdown(title, "flowchart-sections", parts);
    }

    /** folder: 파트 파일이 있는 폴더 (본문의 파일 경로 표시) */
    public static String combinedMarkdown(String title, String folder, List<Part> parts) {
        StringBuilder sb = new StringBuilder("# 흐름도: ").append(title).append("\n\n");
        sb.append("> `").append(folder).append("/` 의 파일을 순서대로 합친 문서입니다. 다른 구간·메서드로 넘어가는 흐름은 ▶ 노드로 끝나고, 그 내용은 아래 해당 절에 있습니다.\n\n");
        for (Part p : parts) {
            sb.append("- [").append(p.title()).append("](#").append(anchor(p.title())).append(")\n");
        }
        for (Part p : parts) {
            sb.append("\n## ").append(p.title()).append("\n\n`").append(folder).append("/").append(p.file()).append("`\n\n```mermaid\n")
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
        // 호출 없는 변수 선언: 그리는 메서드·블록 안이면 바인딩 블록 줄로 매핑되어야 함
        // (단계 보기의 진입 메서드는 단계 요약으로 그리므로 제외)
        r.variableChanges.stream().filter(c -> r.isPlainDeclaration(c) && r.method(c.methodId).analyzed && !skip.contains(c.methodId)
                        && !(rules.omitHandlers() && c.methodId.equals(r.entryMethodId))
                        && (c.controlBlockId == null || !(rules.omitHandlers() && inHandler(r, c.controlBlockId))
                        && !rules.suppressedBlocks().contains(c.controlBlockId))
                        && !rules.suppressedCalls().contains(c.id))
                .forEach(c -> {
                    if (!graph.has(c.id)) {
                        e.add("변수 선언 노드 누락: " + c.id + " `" + c.expression + "` (" + r.method(c.methodId).name + " · "
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

        /**
         * 이어 쌓을 준비. 종류(요청 읽기·VO 값 설정·상태 변화·로그·일반 호출)로 나누지 않고 계속 쌓는다 —
         * 노드를 끊는 것은 제어 블록(if·반복·try…)과 따로 그리는 호출(세부 상자로 가는 호출 등)뿐 (사용자 요구:
         * "바인딩/상태 변화/로그" 제목·색으로 나누는 것이 제대로 나누지 못해 없앰). k 는 예전 호환용으로 무시.
         */
        List<End> start(String k, List<End> cur, String firstId) {
            kind = "block";
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
            lines.add(new Line(callCode(s), stateClass(s), null));
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
            boolean changed = false;
            for (VariableChange c : r.variableChanges) {
                if (c.methodId.equals(first.callerMethodId) && contains(first.statementLocation, c.location)) {
                    line.append(Labels.historySuffix(r, c));
                    changes.remove(c);
                    members.add(c.id);
                    changed |= c.isChange();
                }
            }
            lines.add(new Line(line.toString(), changed ? "state" : null, null));
            group.forEach(c -> members.add(c.id));
            return cur;
        }

        List<End> log(CallSiteInfo s, List<End> cur) {
            return code(s, cur); // 로그도 코드 한 줄 (제목·배경색 구분 없음)
        }

        /** 일반 호출 (세부 상자로 가지 않는 호출) — 색 구분(css)이 같으면 이어 쌓는다 */
        List<End> code(CallSiteInfo s, List<End> cur) {
            cur = start("block", cur, s.id);
            codeCalls.add(s);
            lines.add(new Line(callCode(s), stateClass(s), null));
            String impl = Labels.implementationTarget(r, s);
            if (impl != null) {
                lines.add(new Line("  impl 후보: " + impl, null, null));
            }
            String req = Labels.requestTag(r, s);
            if (req != null) {
                lines.add(new Line("  " + req, null, null));
            }
            Labels.sqlLines(s.sql).forEach(l -> lines.add(new Line("  " + l, null, null)));
            members.add(s.id);
            assignedBy.getOrDefault(s.id, List.of()).forEach(v -> members.add(v.id));
            return cur;
        }

        /** 호출 없는 변수 선언 한 줄: boolean data = false / String label */
        List<End> declare(VariableChange c, List<End> cur) {
            cur = start("bind", cur, "VAR_" + c.id);
            lines.add(new Line(Labels.declarationLine(r, c), null, null));
            members.add(c.id);
            return cur;
        }

        List<End> change(VariableChange c, List<End> cur) {
            cur = start("state", cur, "VAR_" + c.id);
            lines.add(new Line(Labels.changeLine(r, c), "state", null)); // 상태 변화 = 버전 꼬리표 + 주황 글자
            members.add(c.id);
            return cur;
        }

        List<End> flush(List<End> cur) {
            String id = nodeId;
            kind = null;
            nodeId = null;
            List<CallSiteInfo> cs = new ArrayList<>(codeCalls);
            codeCalls.clear();
            if (id == null || lines.isEmpty()) {
                lines.clear();
                members.clear();
                return cur;
            }
            // 호출 하나뿐이면 예전 호출 노드 그대로
            if (cs.size() == 1 && members.stream().allMatch(x -> x.equals(cs.get(0).id)
                    || assignedBy.getOrDefault(cs.get(0).id, List.of()).stream().anyMatch(v -> v.id.equals(x)))) {
                lines.clear();
                members.clear();
                callNode(sg, cs.get(0));
                return link(cur, id);
            }
            // 줄이 많으면 output.max-node-lines 마다 다음 노드(id_p2, id_p3 …)로 이어서 나눈다 (사용자: 100줄 넘는 노드는 안 보임)
            List<List<String>> parts = new ArrayList<>();
            List<String> out = new ArrayList<>();
            String last = null;
            for (Line l : lines) {
                if (maxNodeLines > 0 && out.size() >= maxNodeLines) {
                    parts.add(out);
                    out = new ArrayList<>();
                    last = null; // 다음 노드에서 객체 줄을 다시
                }
                // VO 값 설정 줄: 어느 객체의 줄인지 (객체가 바뀔 때마다 한 줄)
                if (l.objectId() != null && !l.objectId().equals(last)) {
                    var o = r.object(l.objectId());
                    out.add("· " + o.className + (o.variable == null ? "" : " " + o.variable));
                }
                last = l.objectId() != null ? l.objectId() : last;
                out.add(l.textClass() == null ? l.text() : MermaidGraph.styled(l.textClass(), l.text()));
            }
            parts.add(out);
            for (int k = 0; k < parts.size(); k++) {
                String nid = k == 0 ? id : id + "_p" + (k + 1);
                g.node(sg, nid, parts.get(k), Shape.RECT, "codeline");
                cur = link(cur, nid);
            }
            for (String x : members) {
                if (!x.equals(id) && !g.has(x)) {
                    g.alias(x, id);
                }
            }
            lines.clear();
            members.clear();
            return cur;
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
        // 인자 안의 호출(세부 상자로 가서 따로 그리는 기본값 함수 등)은 호출만 — 문장 전체는 바깥 호출 노드가 보인다.
        // 문장 전체를 쓰면 getParameter(req, "x", VasUtil.f(id)) 의 문장이 두 번 그려졌다 (사용자 지적).
        // 바깥 호출이 모델에 없으면(라이브러리 제외 등) 문장 전체를 써야 코드가 사라지지 않는다.
        boolean nested = s.inArgument && r.callSitesOf(s.callerMethodId).stream()
                .anyMatch(o -> o != s && contains(o.location, s.location));
        if (var == null && !nested && s.statement != null && s.statementLocation != null) {
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
            if (c.isChange() || !v.name.equals(var)) {
                code.append(v.name.equals(var) ? "" : " → " + v.name).append(Labels.historySuffix(r, c));
            }
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
        // 호출 없는 변수 선언(boolean data = false, String label;) — 선언 코드 그대로 바인딩 블록 한 줄 (위치 순)
        List<VariableChange> decls = new ArrayList<>(r.declarationsIn(m.id, container).stream()
                .filter(c -> !supCalls.contains(c.id)).toList());
        java.util.Set<String> done = new java.util.HashSet<>(); // 문장 줄로 이미 그린 호출
        for (Object o : elems) {
            if (o instanceof CallSiteInfo cs && (done.contains(cs.id) || inLeaf.contains(cs.id))) {
                continue;
            }
            SourceLocation at = o instanceof CallSiteInfo s ? s.location : ((ControlBlock) o).location;
            cur = preItems(p, decls, orphans, at, cur);
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
        cur = preItems(p, decls, orphans, null, cur);
        for (VariableChange c : changes) {
            cur = p.change(c, cur);
        }
        return p.flush(cur);
    }

    /**
     * at(없으면 끝) 앞에서 시작하는 호출 없는 선언과 호출 없는 바인딩을 위치 순으로 쌓는다.
     * 같은 자리면 선언이 먼저 — OrderVO vo = new OrderVO("A", 1) 의 선언 줄 뒤에 생성자 바인딩 줄.
     */
    private List<End> preItems(Pending p, List<VariableChange> decls, List<Binding> orphans, SourceLocation at, List<End> cur) {
        while (true) {
            VariableChange d = decls.isEmpty() ? null : decls.get(0);
            Binding b = orphans.isEmpty() ? null : orphans.get(0);
            boolean dOk = d != null && (at == null || before(d.location, at));
            boolean bOk = b != null && (at == null || before(b.location, at));
            if (dOk && (!bOk || !before(b.location, d.location))) {
                cur = p.declare(decls.remove(0), cur);
            } else if (bOk) {
                cur = p.orphan(orphans.remove(0), cur);
            } else {
                return cur;
            }
        }
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
        boolean changed = false;
        for (VariableChange c : r.variableChanges) {
            if (c.methodId.equals(first.callerMethodId) && contains(first.statementLocation, c.location)) {
                line.append(Labels.historySuffix(r, c));
                changes.remove(c);
                g.alias(c.id, first.id);
                changed |= c.isChange();
            }
        }
        g.node(sg, first.id, List.of(changed ? MermaidGraph.styled("state", line.toString()) : line.toString()), Shape.RECT, "bind");
        group.stream().skip(1).forEach(c -> g.alias(c.id, first.id));
        return link(cur, first.id);
    }

    /** 연속된 변수 값 변경 → "값 변경" 노드 1개 (ID = VAR_첫변경ID, 나머지 변경 ID 는 alias) */
    private List<End> flushVars(Subgraph sg, List<VariableChange> vars, List<End> cur) {
        if (vars.isEmpty()) {
            return cur;
        }
        List<String> lines = new ArrayList<>();
        vars.forEach(c -> lines.add(MermaidGraph.styled("state", Labels.changeLine(r, c))));
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
                // 반복문 = 반복 영역(subgraph) 하나: 조건 노드 + 본문, 본문 끝에서 조건으로 되돌아간다.
                // if 와 구분: 조건 노드는 육각형(순서도의 반복 기호, if/switch 는 마름모), 되돌아가는 간선 "↻ 다음 반복",
                // 빠져나가는 간선 "반복 끝", 영역 제목 "↻ 반복 (for)" (사용자: 화살표만으로는 if 와 차이가 없음)
                Subgraph ls = g.subgraph(sg, "sg_" + b.id, LOOP_TITLE + " " + switch (b.type) {
                    case FOR -> "for";
                    case FOREACH -> "for-each";
                    case WHILE -> "while";
                    default -> "do-while";
                });
                if (b.type == BlockType.DO_WHILE) {
                    // do-while: 본문을 먼저 지나고 조건 → loop 이면 본문 첫 노드로, done 이면 밖으로
                    Set<String> before = new java.util.LinkedHashSet<>(g.nodeIds());
                    List<End> body = emit(ls, m, b, in);
                    String first = g.nodeIds().stream().filter(id -> !before.contains(id)).findFirst().orElse(null);
                    decision(ls, b, body);
                    // 본문이 비어 있으면 조건(조건식 안 호출 노드가 있으면 그 첫 노드)부터 다시
                    g.edge(b.id, first == null ? loopReentry(b) : first, LOOP_AGAIN, EdgeStyle.FLOW);
                    return List.of(new End(b.id, LOOP_DONE, EdgeStyle.FLOW));
                }
                decision(ls, b, in);
                List<End> back = emit(ls, m, b, List.of(new End(b.id, LOOP_ENTER, EdgeStyle.FLOW)));
                String reentry = loopReentry(b);
                if (back.size() > 1) {
                    // 본문 끝이 여럿(if / else if …)이면 반복 영역 안 합류 노드 ↻ 하나로 모아 조건으로 한 번만 되돌아간다
                    // (사용자: for 안 if/else if 에서 되돌아가는 선이 여러 개라 이상해 보임)
                    String join = "NEXT_" + b.id;
                    g.node(ls, join, List.of("↻"), Shape.ROUND, "loopnext");
                    for (End e : back) {
                        g.edge(e.node(), join, e.label() == null ? LOOP_ENTER : MermaidGraph.LOOP_MARK + e.label(), e.style());
                    }
                    g.edge(join, reentry, LOOP_ENTER, EdgeStyle.FLOW);
                } else {
                    for (End e : back) {
                        g.edge(e.node(), reentry, e.label() == null ? LOOP_NEXT : MermaidGraph.LOOP_MARK + e.label(), e.style());
                    }
                }
                return List.of(new End(b.id, LOOP_DONE, EdgeStyle.FLOW));
            }
            case TRY -> {
                return phases == null ? tryFull(sg, m, b, in) : tryBlock(sg, m, b, in);
            }
            case CATCH, FINALLY -> {
                return in;
            }
            case RETURN, THROW -> {
                // return/throw 식 안의 호출은 리프 노드에 이미 보이므로 따로 그리지 않는다. 세부 박스로 가는 호출
                // (return errorView(…))도 노드를 따로 만들지 않고 리프 노드에서 ==> — 같은 코드가 두 번, 간선이 하나 더
                // 생기던 문제 (사용자 지적)
                List<String> folded = new ArrayList<>();
                List<CallSiteInfo> detail = new ArrayList<>();
                for (Object o : Labels.elements(r, m.id, b.id, false)) {
                    if (o instanceof CallSiteInfo c) {
                        folded.add(c.id);
                        if (leadsToDetail(c) && !excludedCall(r, rules.excludes(), c)) {
                            detail.add(c);
                        }
                    }
                }
                inLeaf.addAll(folded);
                List<End> p = emit(sg, m, b, in);
                leaf(sg, m, b);
                folded.forEach(id -> g.alias(id, b.id));
                for (CallSiteInfo c : detail) {
                    g.edge(b.id, c.expansionTargetId(), callEdgeLabel(c), EdgeStyle.CALL);
                }
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
        boolean loop = switch (b.type) {
            case FOR, FOREACH, WHILE, DO_WHILE -> true;
            default -> false;
        };
        g.node(sg, b.id, headerLines(b), loop ? Shape.HEX : Shape.DIAMOND, null);
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
            // 간선 라벨은 짧게 — 예외 타입은 catch 노드에 이미 있고, try 상자(subgraph)에서 나가는 간선의 라벨은
            // 바로 아래 catch 노드에 가려져 잘려 보였다 (사용자 지적, mmdc 렌더링으로 확인)
            g.edge(ts.id(), c.id, "예외", EdgeStyle.EXCEPTION);
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
            // 간선 라벨은 짧게 — 예외 타입은 catch 노드에 이미 있고, try 상자(subgraph)에서 나가는 간선의 라벨은
            // 바로 아래 catch 노드에 가려져 잘려 보였다 (사용자 지적, mmdc 렌더링으로 확인)
            g.edge(ts.id(), c.id, "예외", EdgeStyle.EXCEPTION);
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
        // return sqlSession.selectList("ns.id", p) / return mapper.selectX(vo) — 합쳐진 호출의 매퍼 SQL
        r.callSites.stream().filter(c -> b.id.equals(c.controlBlockId) && c.sql != null)
                .forEach(c -> lines.addAll(Labels.sqlLines(c.sql)));
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
            // 소스 밖 타입(HttpServletRequest 등)은 시그니처를 몰라 PARTIALLY_RESOLVED 지만 대상 타입은 확정 → 외부 호출 색
            case EXTERNAL, NO_BODY -> req ? "req" : s.resolution == ResolutionStatus.RESOLVED
                    || s.resolution == ResolutionStatus.PARTIALLY_RESOLVED ? "external" : "unresolved";
            default -> req ? "req" : s.resolution == ResolutionStatus.RESOLVED ? "codeline" : "unresolved";
        };
    }

    /**
     * 호출 결과를 이미 있는 변수에 대입하는 줄(x = call(), 타입 없음 = 상태 변화)이면 "state"(주황 글자), 아니면 null.
     * 값 변경 줄만 주황이고 호출 대입은 기본 색이라 같은 상태 변화가 다르게 보였다 (사용자 지적).
     */
    private String stateClass(CallSiteInfo s) {
        return assignedBy.getOrDefault(s.id, List.of()).stream().anyMatch(VariableChange::isChange) ? "state" : null;
    }

    private void callNode(Subgraph sg, CallSiteInfo s) {
        List<String> lines = new ArrayList<>();
        boolean folded = s.targetMethodId != null && collapsed.contains(s.expansionTargetId());
        String var = s.resultUsage != null && s.resultUsage.startsWith("assigned:") ? s.resultUsage.substring(9) : null;
        String code = callCode(s);
        lines.add(stateClass(s) == null ? code : MermaidGraph.styled("state", code)); // x = call() 대입(상태 변화)이면 주황
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
        lines.addAll(Labels.sqlLines(s.sql));
        String req = Labels.requestTag(r, s);
        if (req != null) {
            lines.add(req);
        }
        g.node(sg, s.id, lines, Shape.RECT, callCss(s));
        assignedBy.getOrDefault(s.id, List.of()).forEach(c -> g.alias(c.id, s.id));
        if (s.targetMethodId != null && drawnMethods.contains(s.expansionTargetId())
                && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED
                || s.expansion == Expansion.CYCLE)) {
            g.edge(s.id, s.expansionTargetId(), callEdgeLabel(s), EdgeStyle.CALL);
        }
    }

    private static String callEdgeLabel(CallSiteInfo s) {
        return switch (s.expansion) {
            case SHARED -> "shared";
            case CYCLE -> "cycle";
            default -> s.implementationMethodId != null ? "impl 후보" : null;
        };
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
