package analyzer.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** YAML, 트리, Mermaid 가 공통으로 사용하는 단일 분석 모델. */
public final class AnalysisResult {
    public String sourceRoot;
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

    public List<ControlBlock> blocksOf(String methodId) {
        return blocks.stream().filter(b -> b.methodId.equals(methodId)).toList();
    }
}
