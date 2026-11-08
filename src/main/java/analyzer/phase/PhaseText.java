package analyzer.phase;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.CallSiteInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.VariableChange;
import analyzer.phase.PhaseModel.CheckItem;
import analyzer.phase.PhaseModel.Step;
import analyzer.serializer.Labels;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 단계 보기 표시 문자열 (phase-flowchart.mmd · phase-summary.md 공용). */
public final class PhaseText {
    private PhaseText() {
    }

    /** 요소 표시: 호출식 / 블록 헤더 / 생성자·필드 바인딩 */
    public static String stepLabel(Step st) {
        if (st.element instanceof CallSiteInfo s) {
            if (s.statement != null && st.group.size() > 1) { // 한 문장으로 묶인 요소는 문장 전체
                String t = s.statement;
                return t.endsWith(";") ? t.substring(0, t.length() - 1) : t;
            }
            return s.expression;
        }
        if (st.element instanceof ControlBlock b) {
            return Labels.blockHeader(b);
        }
        if (st.element instanceof VariableChange c) {
            return Labels.truncate(c.expression, 70);
        }
        Binding b = (Binding) st.element;
        return Labels.truncate(b.assignment.toLowerCase() + " " + b.method + " → " + b.field, 70);
    }

    /** 요청 파라미터 이름 (중복 제거, 읽은 순서) */
    public static List<String> parameterNames(List<Step> steps) {
        Set<String> out = new LinkedHashSet<>();
        for (Step st : steps) {
            for (RequestParameterInfo p : st.parameters) {
                out.add(Labels.parameterName(p));
            }
        }
        return new ArrayList<>(out);
    }

    /** 객체별 바인딩 (객체 등장 순서) */
    public static Map<ObjectInfo, List<Binding>> objects(AnalysisResult r, List<Step> steps) {
        Map<ObjectInfo, List<Binding>> out = new LinkedHashMap<>();
        for (Step st : steps) {
            for (Binding b : st.bindings) {
                List<Binding> l = out.computeIfAbsent(r.object(b.objectId), k -> new ArrayList<>());
                if (!l.contains(b)) {
                    l.add(b);
                }
            }
        }
        return out;
    }

    /** ① 바인딩(첫 로직 전)에서 본문을 전개한 source 메서드 */
    public static List<String> bindingMethods(AnalysisResult r, List<Step> steps) {
        Set<String> out = new LinkedHashSet<>();
        for (Step st : steps) {
            if (st.region == PhaseModel.Region.PRE && st.phase == Phase.BINDING) {
                for (String m : st.reachedMethods) {
                    MethodInfo mi = r.method(m);
                    out.add((mi.classId.equals(r.method(r.entryMethodId).classId) ? "" : r.classOf(mi).name + ".") + mi.name + "()");
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** ① 바인딩 요약 노드: 요청 파라미터 (n) / 이름… / VO (n) / 클래스 변수… / 조회: f() → v (상세는 phase-summary.md · mapping.md) */
    public static List<String> bindingLines(AnalysisResult r, List<Step> pre) {
        List<String> l = new ArrayList<>(compact(r, pre));
        for (Step st : pre) {
            l.addAll(fetchLines(st));
        }
        return l;
    }

    /** 값 조회 (코드 형식): user = getLoginUser(request) */
    public static List<String> fetchLines(Step st) {
        List<String> l = new ArrayList<>();
        if (st.fetchVariable == null) {
            return l;
        }
        // 값 조회는 바인딩으로 끝 — 내부(실패 조건 등)는 그리지 않는다
        l.add("조회: " + st.fetchVariable + " = " + (st.element instanceof CallSiteInfo s ? Labels.truncate(s.expression, 60) : stepLabel(st)));
        return l;
    }

    /** 요청 파라미터 + VO 목록 (한 줄에 하나) */
    private static List<String> compact(AnalysisResult r, List<Step> steps) {
        List<String> l = new ArrayList<>();
        List<String> params = parameterNames(steps);
        if (!params.isEmpty()) {
            l.addAll(Labels.paramSummary(params));
        }
        List<String> vos = objects(r, steps).keySet().stream().map(o -> o.className + " " + o.variable).distinct().toList();
        if (!vos.isEmpty()) {
            l.add("VO (" + vos.size() + ")");
            l.addAll(vos);
        }
        List<String> changes = steps.stream().flatMap(st -> st.changes.stream()).map(c -> Labels.changeLine(r, c)).distinct().toList();
        if (!changes.isEmpty()) {
            l.add("값 변경 (" + changes.size() + ")");
            l.addAll(changes);
        }
        return l;
    }

    /** ② 체크 노드 1개 */
    public static List<String> checkLines(CheckItem c) {
        List<String> l = new ArrayList<>();
        l.addAll(Labels.conditionLines(c.condition()));
        l.add("→ " + Labels.truncate(c.outcome(), 90));
        l.add(c.context() + (c.location() == null ? "" : " · L" + c.location().beginLine()));
        return l;
    }

    /** 로직 사이의 바인딩·체크 묶음, ④ 후처리 노드: 요소(메서드명) + 요청 파라미터 + VO */
    public static List<String> inlineLines(AnalysisResult r, List<Step> steps) {
        List<String> l = new ArrayList<>();
        steps.stream().map(st -> st.element instanceof CallSiteInfo s
                        ? (s.expression.contains("(") ? s.expression.substring(0, s.expression.indexOf('(')) : s.expression) + "()"
                : Labels.truncate(stepLabel(st), 50)).distinct().forEach(l::add);
        l.addAll(compact(r, steps));
        return l;
    }

}
