package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.Origin;
import analyzer.model.SourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 트리·Mermaid 공용 "흐름 보기" 규칙. 흐름의 중심은 호출·분기·예외이고, 객체 바인딩은 객체당 요약 1개로 접는다.
 * <ul>
 *   <li>바인딩 코드의 호출(setter/builder/put)과 그 안의 전개되지 않은 호출(인자 속 getParameter 등)은 흐름에서 뺀다</li>
 *   <li>바인딩 메서드(본문이 바인딩 + 최상위 return 뿐 — VO 를 채워 반환하는 메서드)는 그리지 않고, 그 호출 위치에 요약을 둔다</li>
 *   <li>메서드 안의 객체별 첫 바인딩 위치에 요약(Summary) 1개를 둔다</li>
 * </ul>
 * 상세 매핑은 analysis.yaml / mapping.md 에 모두 남는다(모델은 그대로).
 */
public final class FlowView {

    /** 객체 바인딩 요약 1개 (흐름상의 위치 = methodId, blockId, at) */
    public record Summary(String id, String methodId, String blockId, SourceLocation at, ObjectInfo object,
                          List<Binding> bindings) {
    }

    private record View(Set<String> hiddenCalls, Set<String> bindingMethods, List<Summary> summaries) {
    }

    private static final Map<AnalysisResult, View> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private FlowView() {
    }

    private static View view(AnalysisResult r) {
        return CACHE.computeIfAbsent(r, FlowView::compute);
    }

    private static View compute(AnalysisResult r) {
        Set<String> bindingCalls = new HashSet<>();
        for (CallSiteInfo s : r.callSites) {
            if (isBindingCode(r, s)) {
                bindingCalls.add(s.id);
            }
        }
        Set<String> bindingMethods = new LinkedHashSet<>();
        for (MethodInfo m : r.methods()) {
            if (!m.analyzed || m.origin != Origin.SOURCE || m.id.equals(r.entryMethodId)) {
                continue;
            }
            List<CallSiteInfo> calls = r.callSitesOf(m.id);
            boolean onlyReturn = r.blocksOf(m.id).stream().allMatch(b -> b.type == BlockType.RETURN && b.parentBlockId == null);
            boolean hasBinding = r.bindings.stream().anyMatch(b -> b.methodId.equals(m.id));
            if (onlyReturn && hasBinding && calls.stream().allMatch(c -> bindingCalls.contains(c.id))) {
                bindingMethods.add(m.id);
            }
        }
        Set<String> hidden = new HashSet<>(bindingCalls);
        for (CallSiteInfo s : r.callSites) {
            if (bindingMethods.contains(s.callerMethodId)
                    || s.targetMethodId != null && bindingMethods.contains(s.expansionTargetId())) {
                hidden.add(s.id);
            }
        }
        List<Summary> summaries = new ArrayList<>();
        for (MethodInfo m : r.methods()) {
            if (!bindingMethods.contains(m.id)) {
                byObject(r, r.bindings.stream().filter(b -> b.methodId.equals(m.id)).toList()).forEach((o, bs) -> {
                    Anchor a = anchor(r, bs);
                    summaries.add(new Summary("BIND_" + o.id + "_" + m.id, m.id, a.blockId(), a.at(), o, bs));
                });
            }
        }
        for (CallSiteInfo s : r.callSites) {
            if (s.targetMethodId != null && bindingMethods.contains(s.expansionTargetId())
                    && !bindingMethods.contains(s.callerMethodId)) {
                String bm = s.expansionTargetId();
                byObject(r, r.bindings.stream().filter(b -> b.methodId.equals(bm)).toList()).forEach((o, bs) ->
                        summaries.add(new Summary("BIND_" + o.id + "_" + s.id, s.callerMethodId, s.controlBlockId,
                                s.location, o, bs)));
            }
        }
        return new View(hidden, bindingMethods, summaries);
    }

    private record Anchor(String blockId, SourceLocation at) {
    }

    /**
     * 요약 위치: 객체의 모든 바인딩을 감싸는 가장 가까운 공통 컨테이너. if 의 then/else 처럼 서로 다른 분기에 있으면
     * 그 분기 문장(if) 바로 앞에 둔다. 공통 컨테이너가 첫 바인딩의 블록이면 첫 바인딩 위치.
     */
    private static Anchor anchor(AnalysisResult r, List<Binding> bs) {
        List<List<String>> paths = bs.stream().map(b -> containerPath(r, b.controlBlockId)).toList();
        List<String> first = paths.get(0);
        String common = null;
        for (String c : first) {
            if (paths.stream().allMatch(p -> p.contains(c))) {
                common = c;
                break;
            }
        }
        int idx = first.indexOf(common);
        if (idx <= 0) {
            return new Anchor(common, bs.get(0).location);
        }
        ControlBlock child = r.block(first.get(idx - 1));
        ControlBlock stmt = "BRANCH".equals(child.relation) ? r.block(child.parentBlockId) : child;
        return new Anchor(common, stmt.location);
    }

    /** 블록 → 메서드 최상위(null)까지의 컨테이너 경로. BRANCH 블록(else/catch/case…)은 주 블록을 건너뛰고 그 부모로 이어진다 */
    private static List<String> containerPath(AnalysisResult r, String blockId) {
        List<String> path = new ArrayList<>();
        String cur = blockId;
        while (cur != null) {
            path.add(cur);
            ControlBlock b = r.block(cur);
            cur = "BRANCH".equals(b.relation) ? r.block(b.parentBlockId).parentBlockId : b.parentBlockId;
        }
        path.add(null);
        return path;
    }

    /** 바인딩 할당 코드 자체(setter/builder/put 호출)이거나 그 안에 포함된 전개되지 않은 호출 */
    private static boolean isBindingCode(AnalysisResult r, CallSiteInfo s) {
        for (Binding b : r.bindings) {
            if (s.id.equals(b.callSiteId)) {
                return true;
            }
            if (b.methodId.equals(s.callerMethodId) && s.expansion != Expansion.EXPANDED && contains(b.location, s.location)) {
                return true;
            }
        }
        return false;
    }

    private static Map<ObjectInfo, List<Binding>> byObject(AnalysisResult r, List<Binding> bs) {
        Map<ObjectInfo, List<Binding>> out = new LinkedHashMap<>();
        bs.stream().sorted((a, b) -> compare(a.location, b.location))
                .forEach(b -> out.computeIfAbsent(r.object(b.objectId), k -> new ArrayList<>()).add(b));
        return out;
    }

    /** 흐름에서 뺀 호출 (바인딩 코드, 바인딩 메서드 호출/내부) */
    public static boolean hidden(AnalysisResult r, CallSiteInfo s) {
        return view(r).hiddenCalls().contains(s.id);
    }

    /** 흐름에서 뺀 블록 (바인딩 메서드 내부) */
    public static boolean hidden(AnalysisResult r, ControlBlock b) {
        return view(r).bindingMethods().contains(b.methodId);
    }

    /** 흐름에 그리지 않는 바인딩 메서드 */
    public static Set<String> bindingMethods(AnalysisResult r) {
        return view(r).bindingMethods();
    }

    public static List<Summary> summaries(AnalysisResult r) {
        return view(r).summaries();
    }

    /** 메서드(또는 블록) 직계 흐름 요소: 호출·블록(seq 순) + 바인딩 요약(위치 순으로 끼움) */
    public static List<Object> elements(AnalysisResult r, String methodId, String blockId, boolean includeBranches) {
        List<Object> base = Labels.elements(r, methodId, blockId, includeBranches).stream()
                .filter(o -> !(o instanceof CallSiteInfo s && hidden(r, s))).toList();
        List<Object> out = new ArrayList<>(base);
        for (Summary sm : view(r).summaries()) {
            if (!sm.methodId().equals(methodId) || !Objects.equals(sm.blockId(), blockId)) {
                continue;
            }
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                if (compare(sm.at(), location(out.get(i))) <= 0) {
                    at = i;
                    break;
                }
            }
            out.add(at, sm);
        }
        return out;
    }

    private static SourceLocation location(Object o) {
        if (o instanceof CallSiteInfo s) {
            return s.location;
        }
        if (o instanceof ControlBlock b) {
            return b.location;
        }
        return ((Summary) o).at();
    }

    private static int compare(SourceLocation a, SourceLocation b) {
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
