package analyzer.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** YAML, 트리, Mermaid 가 공통으로 사용하는 단일 분석 모델. */
public final class AnalysisResult {
    /** location.file 의 기준 경로 (루트 1개면 그 루트, 여러 개면 공통 상위 디렉터리) */
    public String sourceRoot;
    /** 분석에 쓴 소스 루트 전체 (멀티 모듈) */
    public final java.util.List<String> sourceRoots = new java.util.ArrayList<>();
    public String targetClassId;
    public String entryMethodId;
    public String entrySelection;
    public Map<String, Object> settings = new LinkedHashMap<>();

    public final Map<String, ClassInfo> classesByKey = new LinkedHashMap<>();
    public final Map<String, MethodInfo> methodsByKey = new LinkedHashMap<>();
    public final List<CallSiteInfo> callSites = new ArrayList<>();
    public final List<CallRelation> relations = new ArrayList<>();
    public final List<ControlBlock> blocks = new ArrayList<>();
    public final List<RequestParameterInfo> parameters = new ArrayList<>();
    /** 바인딩 대상 객체 (O*) */
    public final List<ObjectInfo> objects = new ArrayList<>();
    /** 객체 필드 할당 (B*) */
    public final List<Binding> bindings = new ArrayList<>();
    /** 값이 바뀌는 변수 (V*) 와 변경 이력 (VC*) */
    public final List<VariableInfo> variables = new ArrayList<>();
    public final List<VariableChange> variableChanges = new ArrayList<>();
    /** 이름별 요청 스펙 (RQ*) — parameters 의 집계 */
    public final List<RequestSpecEntry> requestSpec = new ArrayList<>();
    /** 분석 중 생략/제약 사항 */
    public final List<String> notes = new ArrayList<>();
    public int excludedExternalCalls;

    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<String, Object> byId = new HashMap<>();

    public String nextId(String prefix) {
        int n = counters.merge(prefix, 1, Integer::sum);
        return prefix + n;
    }

    public void index(String id, Object element) {
        if (byId.putIfAbsent(id, element) != null) {
            throw new IllegalStateException("Duplicate model id: " + id);
        }
    }

    public ClassInfo clazz(String id) {
        return (ClassInfo) byId.get(id);
    }

    public MethodInfo method(String id) {
        return (MethodInfo) byId.get(id);
    }

    public CallSiteInfo callSite(String id) {
        return (CallSiteInfo) byId.get(id);
    }

    public ControlBlock block(String id) {
        return (ControlBlock) byId.get(id);
    }

    public Optional<Object> element(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<ClassInfo> classes() {
        return new ArrayList<>(classesByKey.values());
    }

    public List<MethodInfo> methods() {
        return new ArrayList<>(methodsByKey.values());
    }

    public ClassInfo classOf(MethodInfo m) {
        return clazz(m.classId);
    }

    public List<CallSiteInfo> callSitesOf(String methodId) {
        return callSites.stream().filter(c -> c.callerMethodId.equals(methodId)).toList();
    }

    /** 이 호출 위치에서 읽은 Request Parameter */
    public List<RequestParameterInfo> parametersAt(String callSiteId) {
        return parameters.stream().filter(p -> callSiteId.equals(p.callSiteId)).toList();
    }

    public ObjectInfo object(String id) {
        return (ObjectInfo) byId.get(id);
    }

    public Binding binding(String id) {
        return (Binding) byId.get(id);
    }

    public List<Binding> bindingsOf(ObjectInfo o) {
        return o.bindingIds.stream().map(this::binding).toList();
    }

    public RequestParameterInfo parameter(String id) {
        return (RequestParameterInfo) byId.get(id);
    }

    public VariableInfo variable(String id) {
        return (VariableInfo) byId.get(id);
    }

    public VariableChange variableChange(String id) {
        return (VariableChange) byId.get(id);
    }

    /** 메서드 안 변수 이름으로 (없으면 null) */
    public VariableInfo variableNamed(String methodId, String name) {
        return variables.stream().filter(v -> v.methodId.equals(methodId) && v.name.equals(name)).findFirst().orElse(null);
    }

    /** 메서드(또는 블록 직속)의 실제 상태 변화 (INIT/PARAM 제외), 소스 순서 */
    public List<VariableChange> changesIn(String methodId, String blockId) {
        return variableChanges.stream().filter(c -> c.isChange() && c.methodId.equals(methodId)
                && java.util.Objects.equals(c.controlBlockId, blockId)).toList();
    }

    /** 호출 없는 지역 변수 선언 (선언 식 안에 모델 호출이 없음 — 호출이 있으면 호출 노드·문장 줄이 선언 코드를 보인다) */
    public boolean isPlainDeclaration(VariableChange c) {
        return "INIT".equals(c.op) && c.callSiteIds.isEmpty();
    }

    /** 메서드(또는 블록 직속)의 호출 없는 변수 선언, 소스 순서 */
    public List<VariableChange> declarationsIn(String methodId, String blockId) {
        return variableChanges.stream().filter(c -> isPlainDeclaration(c) && c.methodId.equals(methodId)
                        && java.util.Objects.equals(c.controlBlockId, blockId))
                .sorted(java.util.Comparator.comparingInt((VariableChange c) -> c.location.beginLine())
                        .thenComparingInt(c -> c.location.beginColumn()))
                .toList();
    }

    public List<ControlBlock> blocksOf(String methodId) {
        return blocks.stream().filter(b -> b.methodId.equals(methodId)).toList();
    }
}
