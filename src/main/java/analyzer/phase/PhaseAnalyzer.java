package analyzer.phase;

import analyzer.config.AnalyzerConfig;
import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassRole;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import analyzer.model.SourceLocation;
import analyzer.model.ValueSource;
import analyzer.model.VariableChange;
import analyzer.model.VariableInfo;
import analyzer.phase.PhaseModel.CheckItem;
import analyzer.phase.PhaseModel.Region;
import analyzer.phase.PhaseModel.Step;
import analyzer.serializer.FlowView;
import analyzer.serializer.Labels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 공통 분석 모델 → 단계 보기(PhaseModel). 소스를 다시 파싱하지 않는다.
 *
 * <p>요소 판정 (우선순위 순):
 * <ol>
 *   <li>로직 역할(Service·Biz·DAO·Mapper…) 클래스 호출 → 로직, 체크 역할(Validator) 호출 → 체크</li>
 *   <li>요청 값 읽기(getParameter*·request-parameter-rules), 바인딩 코드(setter·builder·put·바인딩 메서드) → 바인딩</li>
 *   <li>본문을 전개한 메서드 호출 → 본문 판정 (로직 > 바인딩 > 체크)</li>
 *   <li>분기 끝이 throw(진입 메서드는 return 포함)이고 조건 외 내용에 로직·바인딩이 없는 if/switch → 체크.
 *       조건식 안의 요청 읽기는 체크의 일부로 본다</li>
 *   <li>체크 이름 규칙(validate*, check*)의 source 메서드 호출 → 체크</li>
 * </ol>
 * 진입 메서드 최상위 보정: 결과 변수가 바인딩 출처로 쓰이는 호출 → 바인딩(값 조회), 로직·바인딩 없는 소스 메서드 → 로직(계산),
 * 그 밖에 근거 없는 요소 → 앞 요소 단계. 첫 로직 ~ 마지막 로직 사이의 바인딩은 순수 입력값(요청·리터럴·상수)이 없으면
 * 로직(결과 값 설정), 마지막 로직 뒤는 후처리.
 */
public final class PhaseAnalyzer {

    private final AnalysisResult r;
    private final Set<ClassRole> logicRoles;
    private final Set<ClassRole> checkRoles;
    private final List<Pattern> checkNames;
    private final Map<String, Phase> methodPhase = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    private PhaseAnalyzer(AnalysisResult r, AnalyzerConfig.Phases cfg) {
        this.r = r;
        this.logicRoles = roles(cfg.logicRoles);
        this.checkRoles = roles(cfg.checkRoles);
        this.checkNames = cfg.checkMethodNames.stream()
                .map(g -> Pattern.compile(Pattern.quote(g).replace("*", "\\E.*\\Q"))).toList();
    }

    public static PhaseModel analyze(AnalysisResult r, AnalyzerConfig.Phases cfg) {
        return new PhaseAnalyzer(r, cfg).build(cfg);
    }

    private static Set<ClassRole> roles(List<String> labels) {
        Set<ClassRole> s = java.util.EnumSet.noneOf(ClassRole.class);
        labels.forEach(l -> s.add(ClassRole.fromLabel(l).orElseThrow(() -> new IllegalArgumentException("알 수 없는 역할: " + l))));
        return s;
    }

    // ================================================================== build

    private PhaseModel build(AnalyzerConfig.Phases cfg) {
        PhaseModel pm = new PhaseModel();
        pm.logicRoles.addAll(logicRoles);
        pm.rules.add("로직 역할: " + cfg.logicRoles);
        pm.rules.add("체크 역할: " + cfg.checkRoles + " · 체크 메서드 이름: " + cfg.checkMethodNames);
        pm.rules.add("체크 분기: 분기 끝이 throw(진입 메서드는 return 포함)이고 조건 외 내용에 로직·바인딩이 없는 if/switch, Validator 류 메서드의 catch → throw");
        String entry = r.entryMethodId;

        // 1. 최상위 요소 (try 본문 펼침)
        List<Object> elements = new ArrayList<>();
        collect(entry, null, elements);
        // 한 문장 안에 중첩된 호출(인자·수신 객체 속 호출, builder 체인)은 가장 바깥 호출의 요소로 묶는다
        Map<Object, Step> byOuter = new java.util.HashMap<>();
        for (Object o : elements) {
            if (o instanceof CallSiteInfo s) {
                CallSiteInfo outer = outermost(s, elements);
                // 같은 변수 선언/대입 문장의 호출은 한 요소 (boolean f = a() && b())
                Object key = s.statementLocation != null ? s.statementLocation : outer;
                Step st = byOuter.get(key);
                if (st == null) {
                    st = new Step();
                    st.element = outer;
                    st.location = s.statementLocation != null ? s.statementLocation : outer.location;
                    byOuter.put(key, st);
                    pm.steps.add(st);
                }
                st.group.add(s);
                addCall(st, s);
            } else {
                Step st = new Step();
                st.element = o;
                st.location = ((ControlBlock) o).location;
                fillReach(st, entry);
                pm.steps.add(st);
            }
        }
        // 호출 없는 최상위 바인딩(생성자·필드 대입) → 별도 요소
        // 최상위 컨테이너: 메서드 본문(null) + 펼친 try 본문 전부 (try 안에 직접 호출이 없어도 포함)
        Set<String> containers = new HashSet<>(flattenedTries);
        containers.add(null);
        Set<Binding> covered = pm.steps.stream().flatMap(s -> s.bindings.stream()).collect(Collectors.toCollection(
                () -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())));
        for (Binding b : r.bindings) {
            if (b.methodId.equals(entry) && containers.contains(b.controlBlockId) && !covered.contains(b)
                    && (b.controlBlockId == null || r.block(b.controlBlockId).type == BlockType.TRY)) {
                Step st = new Step();
                st.element = b;
                st.location = b.location;
                st.bindings.add(b);
                int at = pm.steps.size();
                for (int i = 0; i < pm.steps.size(); i++) {
                    if (compare(pm.steps.get(i).location, b.location) > 0) {
                        at = i;
                        break;
                    }
                }
                pm.steps.add(at, st);
            }
        }

        // 변수 값 변경: 호출 요소 안(x = call())이면 그 요소에, 블록 안이면 블록 요소에, 그 밖의 최상위 변경은 별도 요소
        for (VariableChange c : r.variableChanges) {
            if (!c.isChange() || !c.methodId.equals(entry)) {
                continue;
            }
            Step owner = null;
            for (Step st : pm.steps) {
                if (st.element instanceof CallSiteInfo && st.group.stream().anyMatch(x -> c.callSiteIds.contains(x.id))
                        && Objects.equals(((CallSiteInfo) st.element).controlBlockId, c.controlBlockId)
                        || st.element instanceof ControlBlock b && c.controlBlockId != null && under(c.controlBlockId, b.id)) {
                    owner = st;
                    break;
                }
            }
            if (owner != null) {
                if (c.controlBlockId == null || !handlerBetween(c.controlBlockId, null)) {
                    owner.changes.add(c);
                }
            } else if (containers.contains(c.controlBlockId)
                    && (c.controlBlockId == null || r.block(c.controlBlockId).type == BlockType.TRY)) {
                Step st = new Step();
                st.element = c;
                st.location = c.location;
                st.changes.add(c);
                int at = pm.steps.size();
                for (int i = 0; i < pm.steps.size(); i++) {
                    if (compare(pm.steps.get(i).location, c.location) > 0) {
                        at = i;
                        break;
                    }
                }
                pm.steps.add(at, st);
            }
        }

        // 2. 요소 판정
        for (Step st : pm.steps) {
            classify(st);
        }
        // 3a. 로직·체크·바인딩 근거가 없는 호출에 요청 객체를 넘김 = 요청(세션) 컨텍스트 값 조회 → 바인딩
        Set<String> requestVars = requestVariables();
        for (Step st : pm.steps) {
            if (st.phase == Phase.NONE && st.element instanceof CallSiteInfo s && !requestVars.isEmpty()
                    && s.arguments.stream().anyMatch(requestVars::contains)) {
                st.phase = Phase.BINDING;
                st.reason = "요청 컨텍스트 값 조회 (request 전달)";
                if (s.resultUsage != null && s.resultUsage.startsWith("assigned:")) {
                    st.fetchVariable = s.resultUsage.substring("assigned:".length());
                }
            }
        }
        // 3b. 로직 역할 호출·바인딩이 없는 source 메서드 = 계산·가공 로직
        for (Step st : pm.steps) {
            if (st.phase == Phase.NONE && st.element instanceof CallSiteInfo s && expanded(s)
                    && r.method(s.expansionTargetId()).origin == Origin.SOURCE) {
                st.phase = Phase.LOGIC;
                st.reason = "계산·가공 메서드 (로직 역할 호출·바인딩 없음)";
            }
        }
        // 3c. (3a·3b 로도 판정 안 된) 로직 역할 호출도, 본문을 전개한 메서드도 없는 변수 선언 문장 = 값 준비(바인딩)
        for (Step st : pm.steps) {
            if (st.phase == Phase.NONE && st.element instanceof CallSiteInfo s && "DECLARATION".equals(s.statementKind)
                    && st.group.stream().noneMatch(this::expanded)) {
                st.phase = Phase.BINDING;
                st.reason = "값 준비 (유틸·조회만 쓰는 변수 선언)";
            }
        }
        // 4. 값 조회: 결과 변수가 바로 뒤 바인딩(사이에 로직 없음)에 쓰이고, 그 뒤에 로직이 더 있는 호출 → 바인딩
        for (int i = 0; i < pm.steps.size(); i++) {
            Step st = pm.steps.get(i);
            if (st.element instanceof CallSiteInfo s && st.phase != Phase.BINDING && st.phase != Phase.END
                    && s.resultUsage != null && s.resultUsage.startsWith("assigned:")) {
                String v = s.resultUsage.substring("assigned:".length());
                if (feedsBindingBeforeLogic(pm, i, v)) {
                    st.reason = "값 조회 → " + v + " (뒤 바인딩의 출처, 이후 로직 있음) · " + st.reason;
                    st.phase = Phase.BINDING;
                    st.fetchVariable = v;
                }
            }
        }
        // 5. 근거 없는 요소 → 앞(없으면 뒤) 요소 단계
        for (int i = 0; i < pm.steps.size(); i++) {
            Step st = pm.steps.get(i);
            if (st.phase != Phase.NONE) {
                continue;
            }
            Phase prev = Phase.NONE;
            for (int j = i - 1; j >= 0 && prev == Phase.NONE; j--) {
                prev = pm.steps.get(j).phase == Phase.END ? Phase.NONE : pm.steps.get(j).phase;
            }
            Phase next = Phase.NONE;
            for (int j = i + 1; j < pm.steps.size() && next == Phase.NONE; j++) {
                Step n = pm.steps.get(j);
                next = n.phase == Phase.END || n.phase == Phase.NONE ? Phase.NONE : n.phase;
            }
            st.phase = prev != Phase.NONE ? prev : next != Phase.NONE ? next : Phase.LOGIC;
            st.reason = "판정 근거 없음 → " + (prev != Phase.NONE ? "앞" : next != Phase.NONE ? "뒤" : "기본") + " 요소 단계";
        }
        // 5. 영역
        for (int i = 0; i < pm.steps.size(); i++) {
            pm.steps.get(i).index = i + 1;
            if (pm.steps.get(i).phase == Phase.LOGIC) {
                pm.lastLogic = i;
                if (pm.firstLogic < 0) {
                    pm.firstLogic = i;
                }
            }
        }
        for (int i = 0; i < pm.steps.size(); i++) {
            Step st = pm.steps.get(i);
            if (pm.firstLogic < 0 || i < pm.firstLogic) {
                st.region = Region.PRE;
            } else if (i <= pm.lastLogic) {
                st.region = Region.LOGIC;
                if (st.phase == Phase.BINDING && !pureInput(st)) {
                    st.phase = Phase.LOGIC;
                    st.reason = "로직 결과 값 설정 (요청·리터럴·상수만으로 된 값 없음) · " + st.reason;
                }
            } else {
                st.region = Region.POST;
                if (st.phase != Phase.END) {
                    st.reason = "마지막 로직 뒤 · " + st.phase.label() + ": " + st.reason;
                    st.phase = Phase.POST;
                }
            }
        }
        // 6. 체크 항목 + 흐름도 요약 대상
        for (Step st : pm.steps) {
            collectChecks(st, entry);
            boolean drawnNormally = st.region == Region.LOGIC && st.phase == Phase.LOGIC || st.phase == Phase.END;
            if (!drawnNormally) {
                suppress(pm, st, entry);
            }
        }
        return pm;
    }

    /** 진입 메서드에서 본문을 펼친 try 블록 */
    private final Set<String> flattenedTries = new HashSet<>();

    private void collect(String methodId, String blockId, List<Object> out) {
        for (Object o : Labels.elements(r, methodId, blockId, false)) {
            if (o instanceof ControlBlock b && b.type == BlockType.TRY) {
                flattenedTries.add(b.id);
                for (String cid : b.callIds) { // try-with-resources 리소스 초기화 호출
                    CallSiteInfo s = r.callSite(cid);
                    if (s != null && Objects.equals(s.controlBlockId, b.id) && "RESOURCE".equals(s.position)) {
                        out.add(s);
                    }
                }
                collect(methodId, b.id, out);
            } else if (!(o instanceof CallSiteInfo s && "RESOURCE".equals(s.position))) {
                out.add(o);
            }
        }
    }

    // ================================================================== reach

    private void fillReach(Step st, String methodId) {
        if (st.element instanceof CallSiteInfo s) {
            addCall(st, s);
        } else if (st.element instanceof ControlBlock b) {
            for (ControlBlock x : blocksUnder(methodId, b.id)) {
                st.blocks.add(x);
            }
            for (CallSiteInfo s : callsUnder(methodId, b.id)) {
                addCall(st, s);
            }
            for (Binding bd : r.bindings) {
                if (bd.methodId.equals(methodId) && bd.controlBlockId != null && under(bd.controlBlockId, b.id)
                        && !handlerBetween(bd.controlBlockId, b.id)) {
                    addBinding(st, bd);
                }
            }
        }
    }

    private void addCall(Step st, CallSiteInfo s) {
        if (st.calls.contains(s)) {
            return;
        }
        st.calls.add(s);
        st.parameters.addAll(r.parametersAt(s.id));
        for (Binding b : r.bindings) {
            if (s.id.equals(b.callSiteId) || b.methodId.equals(s.callerMethodId)
                    && Objects.equals(b.controlBlockId, s.controlBlockId) && contains(s.location, b.location)) {
                addBinding(st, b);
            }
        }
        if (expanded(s) && !s.expansionTargetId().equals(r.entryMethodId) && st.reachedMethods.add(s.expansionTargetId())) {
            String m = s.expansionTargetId();
            st.blocks.addAll(blocksUnder(m, null));
            for (CallSiteInfo c : callsUnder(m, null)) {
                addCall(st, c);
            }
            for (Binding b : r.bindings) {
                if (b.methodId.equals(m) && (b.controlBlockId == null || !handlerBetween(b.controlBlockId, null))) {
                    addBinding(st, b);
                }
            }
        }
    }

    private static void addBinding(Step st, Binding b) {
        if (!st.bindings.contains(b)) {
            st.bindings.add(b);
        }
    }

    private boolean expanded(CallSiteInfo s) {
        return s.targetMethodId != null && (s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED)
                && r.method(s.expansionTargetId()).analyzed;
    }

    /** 메서드(또는 블록 하위)의 호출. catch/finally 안은 제외 */
    private List<CallSiteInfo> callsUnder(String methodId, String blockId) {
        return r.callSites.stream().filter(s -> s.callerMethodId.equals(methodId)
                && (blockId == null || s.controlBlockId != null && under(s.controlBlockId, blockId))
                && (s.controlBlockId == null || !handlerBetween(s.controlBlockId, blockId))).toList();
    }

    private List<ControlBlock> blocksUnder(String methodId, String blockId) {
        return r.blocks.stream().filter(b -> b.methodId.equals(methodId)
                && (blockId == null || under(b.id, blockId)) && !handlerBetween(b.id, blockId)).toList();
    }

    /** cur 가 anc 자신이거나 그 하위 */
    private boolean under(String cur, String anc) {
        for (String c = cur; c != null; c = r.block(c).parentBlockId) {
            if (c.equals(anc)) {
                return true;
            }
        }
        return false;
    }

    /** cur ~ stop(제외) 사이에 catch/finally 가 있는지 */
    private boolean handlerBetween(String cur, String stop) {
        for (String c = cur; c != null && !c.equals(stop); c = r.block(c).parentBlockId) {
            BlockType t = r.block(c).type;
            if (t == BlockType.CATCH || t == BlockType.FINALLY) {
                return true;
            }
        }
        return false;
    }

    // ================================================================== classify

    private void classify(Step st) {
        if (st.element instanceof Binding b) {
            st.phase = Phase.BINDING;
            st.reason = "바인딩 (" + b.assignment.toLowerCase() + " " + b.method + ")";
        } else if (st.element instanceof VariableChange c) {
            VariableInfo v = r.variable(c.variableId);
            if (requestDerived(v)) {
                st.phase = Phase.BINDING;
                st.reason = "요청 값 보정 (" + v.name + ")";
            } else {
                st.phase = Phase.NONE;
                st.reason = "변수 값 변경 (" + v.name + ")";
            }
        } else if (st.element instanceof ControlBlock b && b.type.isLeaf()) {
            st.phase = Phase.END;
            st.reason = "최상위 " + b.type.yamlName();
        } else if (st.element instanceof CallSiteInfo s) {
            Phase best = Phase.NONE;
            String reason = null;
            for (CallSiteInfo c : st.group) {
                String[] why = new String[1];
                Phase p = callPhase(c, why);
                if (p != Phase.NONE && Phase.max(best, p) == p && p != best) {
                    best = p;
                    reason = why[0] + (c == s ? "" : " (" + Labels.truncate(c.expression, 40) + ")");
                }
            }
            st.phase = best;
            st.reason = reason == null ? "판정 근거 없음" : reason;
        } else {
            ControlBlock b = (ControlBlock) st.element;
            String[] why = new String[1];
            st.phase = blockPhase(r.entryMethodId, b, why);
            st.reason = why[0] == null ? "판정 근거 없음" : why[0];
        }
    }

    private ClassRole roleOf(String methodId) {
        return methodId == null ? null : r.classOf(r.method(methodId)).role;
    }

    private Phase atom(CallSiteInfo s, String[] why) {
        for (String t : new String[]{s.targetMethodId, s.implementationMethodId}) {
            ClassRole role = roleOf(t);
            if (role != null && logicRoles.contains(role)) {
                return because(why, Phase.LOGIC, role.label() + " 호출");
            }
            if (role != null && checkRoles.contains(role)) {
                return because(why, Phase.CHECK, role.label() + " 호출");
            }
        }
        if (!r.parametersAt(s.id).isEmpty()) {
            return because(why, Phase.BINDING, "요청 값 읽기");
        }
        if (FlowView.hidden(r, s)) {
            return because(why, Phase.BINDING, "바인딩 코드");
        }
        return null;
    }

    private Phase callPhase(CallSiteInfo s, String[] why) {
        Phase a = atom(s, why);
        if (a != null) {
            return a;
        }
        if (s.targetMethodId != null && (expanded(s) || s.expansion == Expansion.CYCLE)) {
            Phase mp = methodPhase(s.expansionTargetId());
            if (mp != Phase.NONE) {
                return because(why, mp, "메서드 본문: " + mp.label());
            }
        }
        if (s.targetMethodId != null && r.method(s.targetMethodId).origin == Origin.SOURCE && checkName(s.methodName)) {
            return because(why, Phase.CHECK, "이름 규칙 " + s.methodName + (expanded(s) ? "" : " (본문 미분석)"));
        }
        return Phase.NONE;
    }

    private boolean checkName(String name) {
        return checkNames.stream().anyMatch(p -> p.matcher(name).matches());
    }

    private Phase methodPhase(String id) {
        Phase memo = methodPhase.get(id);
        if (memo != null) {
            return memo;
        }
        if (!inProgress.add(id)) {
            return Phase.NONE;
        }
        MethodInfo m = r.method(id);
        ClassRole role = r.classOf(m).role;
        Phase p;
        if (logicRoles.contains(role)) {
            p = Phase.LOGIC;
        } else if (checkRoles.contains(role)) {
            p = Phase.CHECK;
        } else {
            p = aggregate(id, null, new String[1]);
            if (p == Phase.NONE && hasGuard(id, null)) {
                p = Phase.CHECK;
            }
        }
        inProgress.remove(id);
        methodPhase.put(id, p);
        return p;
    }

    private Phase blockPhase(String methodId, ControlBlock b, String[] why) {
        Phase agg = aggregate(methodId, b.id, why);
        if (agg == Phase.NONE || agg == Phase.CHECK) {
            if (guardish(b) || hasGuard(methodId, b.id)) {
                return because(why, Phase.CHECK, "분기 종료형 조건 (throw/return)");
            }
        }
        return agg;
    }

    /** 하위 호출·바인딩 단계 집계. 체크 분기의 조건식 안 호출(요청 읽기 등)은 체크의 일부로 보고 제외 */
    private Phase aggregate(String methodId, String blockId, String[] why) {
        Phase agg = Phase.NONE;
        for (CallSiteInfo s : callsUnder(methodId, blockId)) {
            if (guardHeader(s)) {
                continue;
            }
            String[] w = new String[1];
            Phase p = callPhase(s, w);
            if (Phase.max(agg, p) != agg || agg == Phase.NONE && p != Phase.NONE) {
                agg = p;
                why[0] = "포함: " + Labels.truncate(s.expression, 50) + " — " + w[0];
            }
        }
        boolean adjusts = r.variableChanges.stream().anyMatch(c -> c.isChange() && c.methodId.equals(methodId)
                && (blockId == null || c.controlBlockId != null && under(c.controlBlockId, blockId))
                && (c.controlBlockId == null || !handlerBetween(c.controlBlockId, blockId))
                && requestDerived(r.variable(c.variableId)));
        if (adjusts && agg == Phase.NONE) {
            agg = Phase.BINDING;
            why[0] = "요청 값 보정 (변수 값 변경)";
        }
        boolean binds = r.bindings.stream().anyMatch(b -> b.methodId.equals(methodId)
                && (blockId == null || b.controlBlockId != null && under(b.controlBlockId, blockId))
                && (b.controlBlockId == null || !handlerBetween(b.controlBlockId, blockId)));
        if (binds && Phase.max(agg, Phase.BINDING) == Phase.BINDING && agg != Phase.BINDING) {
            agg = Phase.BINDING;
            why[0] = "바인딩 포함";
        }
        return agg;
    }

    private boolean guardHeader(CallSiteInfo s) {
        if (s.controlBlockId == null || !("CONDITION".equals(s.position) || "SELECTOR".equals(s.position))) {
            return false;
        }
        ControlBlock b = r.block(s.controlBlockId);
        return guardish(b.type == BlockType.ELSE_IF ? r.block(b.parentBlockId) : b);
    }

    private static Phase because(String[] why, Phase p, String reason) {
        why[0] = reason;
        return p;
    }

    // ================================================================== guards

    private boolean entryMethod(String methodId) {
        return methodId.equals(r.entryMethodId);
    }

    /** 분기 본문이 바로 throw(진입 메서드는 return 포함)로 끝나는지 */
    private ControlBlock exit(ControlBlock branch) {
        ControlBlock ret = null;
        for (String cid : branch.childBlockIds) {
            ControlBlock c = r.block(cid);
            if ("BRANCH".equals(c.relation)) {
                continue;
            }
            if (c.type == BlockType.THROW) {
                return c;
            }
            if (c.type == BlockType.RETURN && entryMethod(branch.methodId)) {
                ret = c;
            }
        }
        return ret;
    }

    /** if/switch 중 종료형 분기가 있는지 */
    private boolean guardish(ControlBlock b) {
        if (b.type != BlockType.IF && b.type != BlockType.SWITCH) {
            return false;
        }
        if (b.type == BlockType.IF && exit(b) != null) {
            return true;
        }
        return b.childBlockIds.stream().map(r::block).anyMatch(c -> "BRANCH".equals(c.relation) && exit(c) != null);
    }

    private boolean hasGuard(String methodId, String blockId) {
        List<CheckItem> tmp = new ArrayList<>();
        guards(methodId, blockId, "", tmp);
        return !tmp.isEmpty();
    }

    /** 블록(또는 메서드 전체) 안의 체크 항목: 종료형 분기, (진입 메서드 외) catch → throw */
    private void guards(String methodId, String blockId, String context, List<CheckItem> out) {
        List<ControlBlock> bs = r.blocks.stream().filter(b -> b.methodId.equals(methodId)
                && (blockId == null || under(b.id, blockId))).sorted(java.util.Comparator.comparingInt(b -> b.seq)).toList();
        for (ControlBlock b : bs) {
            boolean branch = switch (b.type) {
                case IF, ELSE_IF, ELSE, CASE -> true;
                default -> false;
            };
            if (branch && !handlerBetween(b.id, blockId)) {
                ControlBlock leaf = exit(b);
                if (leaf != null) {
                    out.add(new CheckItem(path(b, blockId) + branchCondition(b), outcome(leaf), context, methodId, b.id, b.location));
                }
            } else if (b.type == BlockType.CATCH && !entryMethod(methodId) && !handlerBetween(b.parentBlockId, blockId)) {
                ControlBlock leaf = exit(b);
                if (leaf != null && leaf.type == BlockType.THROW) {
                    out.add(new CheckItem(path(b, blockId) + "catch " + String.join(" | ", b.exceptionTypes), outcome(leaf),
                            context, methodId, b.id, b.location));
                }
            }
        }
    }

    private String branchCondition(ControlBlock b) {
        return switch (b.type) {
            case IF, ELSE_IF -> b.condition;
            case ELSE -> {
                ControlBlock primary = r.block(b.parentBlockId);
                yield "!(" + primary.condition + ")";
            }
            case CASE -> {
                ControlBlock sw = r.block(b.parentBlockId);
                yield b.caseLabels.isEmpty() ? sw.condition + " : default"
                        : sw.condition + " == " + String.join(" | ", b.caseLabels);
            }
            default -> Labels.blockHeader(b);
        };
    }

    /** 상위 조건 경로 "cond › loop: … › " (stop 블록 위로는 올라가지 않음) */
    private String path(ControlBlock b, String stop) {
        List<String> parts = new ArrayList<>();
        String cur = "BRANCH".equals(b.relation) ? r.block(b.parentBlockId).parentBlockId : b.parentBlockId;
        if (b.id.equals(stop) || "BRANCH".equals(b.relation) && b.parentBlockId.equals(stop)) {
            cur = null;
        }
        while (cur != null) {
            ControlBlock a = r.block(cur);
            switch (a.type) {
                case IF, ELSE_IF, ELSE, CASE -> parts.add(0, branchCondition(a));
                case FOR, FOREACH, WHILE, DO_WHILE -> parts.add(0, "loop: " + a.condition);
                default -> {
                }
            }
            if (cur.equals(stop) || "BRANCH".equals(a.relation) && a.parentBlockId.equals(stop)) {
                break;
            }
            cur = "BRANCH".equals(a.relation) ? r.block(a.parentBlockId).parentBlockId : a.parentBlockId;
        }
        return parts.isEmpty() ? "" : String.join(" › ", parts) + " › ";
    }

    private String outcome(ControlBlock leaf) {
        String s = Labels.blockHeader(leaf);
        if (leaf.type == BlockType.THROW) {
            if (leaf.caughtBy != null) {
                ControlBlock c = r.block(leaf.caughtBy);
                List<String> how = new ArrayList<>();
                if (!c.errorResponseCalls.isEmpty()) {
                    how.add("오류 응답");
                }
                if (c.containsReturn) {
                    how.add("return");
                }
                if (c.rethrows || c.throwsNewException) {
                    how.add("throw");
                }
                s += " → " + Labels.blockHeader(c) + (how.isEmpty() ? "" : " (" + String.join(" · ", how) + ")");
            } else if (entryMethod(leaf.methodId) && Boolean.TRUE.equals(leaf.propagatesToCaller)) {
                s += " → 호출자로 전파";
            }
        }
        return s;
    }

    private void collectChecks(Step st, String entry) {
        String prefix = st.phase == Phase.BINDING ? "바인딩 중 · " : st.phase == Phase.LOGIC ? "로직 중 · " : "";
        if (st.element instanceof ControlBlock b) {
            guards(entry, b.id, prefix + "진입 메서드", st.checks);
        }
        for (CallSiteInfo s : st.calls) {
            if (s.targetMethodId == null || !s.callerMethodId.equals(entry) && !st.reachedMethods.contains(s.callerMethodId)) {
                continue;
            }
            MethodInfo t = r.method(s.expansionTargetId());
            boolean checkCall = checkRoles.contains(r.classOf(t).role)
                    || t.origin == Origin.SOURCE && checkName(s.methodName) && !logicRoles.contains(r.classOf(t).role);
            if (checkCall && !expanded(s) && st.fetchVariable == null) {
                st.checks.add(new CheckItem(Labels.truncate(s.expression, 80),
                        "본문 미분석 (" + s.expansion.name().toLowerCase().replace('_', '-') + ")",
                        prefix + r.classOf(t).name + "." + t.name + "()", s.callerMethodId, null, s.location));
            }
        }
        for (String m : st.reachedMethods) {
            MethodInfo mi = r.method(m);
            if (logicRoles.contains(r.classOf(mi).role) && st.phase != Phase.LOGIC) {
                continue;
            }
            guards(m, null, prefix + r.classOf(mi).name + "." + mi.name + "()", st.checks);
        }
    }

    // ================================================================== helpers

    /** 진입 메서드의 요청 객체 파라미터 이름 (HttpServletRequest / ServletRequest / WebRequest …) */
    private Set<String> requestVariables() {
        Set<String> out = new HashSet<>();
        for (var p : r.method(r.entryMethodId).parameters) {
            String t = p.type() == null ? "" : p.type();
            if (t.endsWith("ServletRequest") || t.endsWith("WebRequest") || t.endsWith("HttpRequest")) {
                out.add(p.name());
            }
        }
        return out;
    }

    /** 선언 초기값(또는 첫 값)이 요청 파라미터에서 온 변수 */
    private boolean requestDerived(VariableInfo v) {
        return v.changeIds.stream().map(r::variableChange).filter(c -> !c.isChange())
                .anyMatch(c -> c.sources.stream().anyMatch(x -> x.kind().equals("REQUEST")));
    }

    /** 같은 컨테이너에서 s 를 감싸는 가장 바깥 호출 (없으면 s) */
    private static CallSiteInfo outermost(CallSiteInfo s, List<Object> elements) {
        CallSiteInfo best = s;
        for (Object o : elements) {
            if (o instanceof CallSiteInfo c && c != s && Objects.equals(c.controlBlockId, s.controlBlockId)
                    && Objects.equals(c.position, s.position) && contains(c.location, best.location)) {
                best = c;
            }
        }
        return best;
    }

    /**
     * 값 조회 판정: i 뒤의 바인딩 요소가 변수 v 를 쓰고(바인딩 값식 또는 바인딩 호출 인자), i 와 그 요소 사이에 로직이 없으며,
     * 그 요소 뒤에 로직이 더 있다 (= 로직 결과를 응답에 담는 것이 아니라 입력을 채우는 조회).
     */
    private boolean feedsBindingBeforeLogic(PhaseModel pm, int i, String v) {
        Pattern word = Pattern.compile("(?<![\\w.])" + Pattern.quote(v) + "\\b");
        for (int j = i + 1; j < pm.steps.size(); j++) {
            Step st = pm.steps.get(j);
            if (st.phase == Phase.LOGIC) {
                return false;
            }
            if (st.phase != Phase.BINDING) {
                continue;
            }
            boolean uses = st.bindings.stream().anyMatch(b -> b.methodId.equals(r.entryMethodId)
                    && b.valueExpression != null && word.matcher(b.valueExpression).find())
                    || st.group.stream().anyMatch(c -> c.arguments.stream().anyMatch(a -> word.matcher(a).find()));
            if (uses) {
                return pm.steps.subList(j + 1, pm.steps.size()).stream().anyMatch(x -> x.phase == Phase.LOGIC);
            }
        }
        return false;
    }

    /** 요청 읽기 또는 출처가 요청·리터럴·상수뿐인 바인딩이 있는지 */
    private static boolean pureInput(Step st) {
        if (!st.parameters.isEmpty()) {
            return true;
        }
        for (Binding b : st.bindings) {
            if (!b.sources.isEmpty() && b.sources.stream().map(ValueSource::kind)
                    .allMatch(k -> k.equals("REQUEST") || k.equals("LITERAL") || k.equals("CONSTANT"))) {
                return true;
            }
        }
        return false;
    }

    private void suppress(PhaseModel pm, Step st, String entry) {
        st.changes.forEach(c -> pm.suppressedCalls.add(c.id)); // 변경 ID 도 같은 집합(요약 노드로 대체)
        if (st.element instanceof CallSiteInfo) {
            st.group.forEach(c -> pm.suppressedCalls.add(c.id));
        } else if (st.element instanceof ControlBlock b) {
            for (ControlBlock x : r.blocks) {
                if (x.methodId.equals(entry) && under(x.id, b.id)) {
                    pm.suppressedBlocks.add(x.id);
                }
            }
            for (CallSiteInfo s : r.callSites) {
                if (s.callerMethodId.equals(entry) && s.controlBlockId != null && under(s.controlBlockId, b.id)) {
                    pm.suppressedCalls.add(s.id);
                }
            }
        }
    }

    static int compare(SourceLocation a, SourceLocation b) {
        if (a == null || b == null) {
            return 0;
        }
        return a.beginLine() != b.beginLine() ? Integer.compare(a.beginLine(), b.beginLine())
                : Integer.compare(a.beginColumn(), b.beginColumn());
    }

    private static boolean contains(SourceLocation outer, SourceLocation inner) {
        if (outer == null || inner == null || !Objects.equals(outer.file(), inner.file())) {
            return false;
        }
        boolean begin = outer.beginLine() < inner.beginLine()
                || outer.beginLine() == inner.beginLine() && outer.beginColumn() <= inner.beginColumn();
        boolean end = inner.endLine() < outer.endLine()
                || inner.endLine() == outer.endLine() && inner.endColumn() <= outer.endColumn();
        return begin && end;
    }
}
