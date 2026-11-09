package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.MethodParameter;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.RequestSpecEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static analyzer.serializer.MappingReportWriter.md;

/**
 * spec.md — 분석이 끝난 결과를 명세 문서로 정리한다. 장 단위로 늘려 간다 (현재: 1. Request Param 정의).
 * Request Param 정의 = analysis.md 1장을 명세 형태로: 개요 → 파라미터 목록(타입·필수·기본값·검증·저장 위치) →
 * 파라미터별 상세 → 요청 값을 담는 객체(ParamMgr·VO 등) → 수동 확인 항목.
 * 모델에서만 만든다(소스를 다시 읽지 않음). 확인할 수 없는 값은 "확인 불가"로 남기고 추측하지 않는다.
 */
public final class SpecWriter {

    public String render(AnalysisResult r) {
        StringBuilder sb = new StringBuilder();
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        sb.append("# 명세: ").append(target.name).append('.').append(entry.name).append("()\n\n");
        sb.append("> 정적 분석 결과(`analysis.yaml`)에서 만든 명세입니다. 진입 메서드에서 도달해 본문을 분석한 코드만 포함합니다. ")
                .append("소스로 확인할 수 없는 항목(필수 여부 등)은 `확인 불가`로 둡니다. ID(P*, RQ*, B*, CALL*)는 analysis.md · analysis.yaml 과 같습니다.\n\n");

        sb.append("## 1. Request Param 정의\n\n");
        overview(r, target, entry, sb);
        list(r, sb);
        details(r, sb);
        holders(r, sb);
        manual(r, sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 1

    private void overview(AnalysisResult r, ClassInfo target, MethodInfo entry, StringBuilder sb) {
        sb.append("### 1.1 개요\n\n| 항목 | 값 |\n|---|---|\n");
        sb.append("| 진입 | `").append(target.qualifiedName).append('.').append(entry.name).append("()` (")
                .append(target.role.label()).append(") |\n");
        List<String> mapping = entry.annotations.stream().filter(a -> a.matches("@?\\w*Mapping\\b.*")).toList();
        if (!mapping.isEmpty()) {
            sb.append("| 매핑 | ").append(md(String.join(", ", mapping))).append(" |\n");
        }
        sb.append("| 입력 | ").append(entry.parameters.isEmpty() ? "-"
                : md(String.join(", ", entry.parameters.stream().map(p -> p.type() + " " + p.name()).toList()))).append(" |\n");
        long unknown = r.parameters.stream().filter(p -> "UNKNOWN".equals(p.nameResolution)).count();
        long bulk = r.parameters.stream().filter(p -> !p.named() && !"UNKNOWN".equals(p.nameResolution)).count();
        sb.append("| 요청 파라미터 | ").append(r.requestSpec.size()).append("개 (읽는 곳 ")
                .append(r.parameters.stream().filter(RequestParameterInfo::named).count()).append("곳)")
                .append(unknown == 0 ? "" : " · 이름 미확정 " + unknown + "곳")
                .append(bulk == 0 ? "" : " · 전체 조회/객체 바인딩 " + bulk + "곳").append(" |\n");
        List<String> holders = holderObjects(r).stream().map(o -> "`" + o.className + "`" + (o.variable == null ? "" : " (" + o.variable + ")"))
                .distinct().toList();
        sb.append("| 요청 값을 담는 객체 | ").append(holders.isEmpty() ? "-" : String.join(", ", holders)).append(" |\n\n");
    }

    // ------------------------------------------------------------------ 2

    private void list(AnalysisResult r, StringBuilder sb) {
        sb.append("### 1.2 파라미터 목록\n\n");
        if (r.requestSpec.isEmpty()) {
            sb.append("이름이 확인된 요청 파라미터 없음.\n\n");
            return;
        }
        sb.append("| No | 파라미터 | 타입 | 필수 | 기본값 | 검증 조건 | 저장 위치 | 읽는 곳 |\n|---|---|---|---|---|---|---|---|\n");
        int no = 1;
        for (RequestSpecEntry e : r.requestSpec) {
            sb.append("| ").append(no++).append(" | **`").append(e.name).append("`** | ")
                    .append(e.types.isEmpty() ? "확인 불가" : md(String.join(", ", e.types))).append(" | ")
                    .append(required(e)).append(" | ")
                    .append(e.defaultValues.isEmpty() ? "-" : md(String.join(", ", e.defaultValues))).append(" | ")
                    .append(join(validations(r, e))).append(" | ")
                    .append(join(stored(r, e))).append(" | ")
                    .append(join(e.parameterIds.stream().map(id -> readAt(r, r.parameter(id))).toList())).append(" |\n");
        }
        sb.append("\n> 필수: Spring `@RequestParam(required)` 처럼 소스에 적힌 경우만 Y/N. `getParameter` 류는 소스로 확인할 수 없어 `확인 불가` ")
                .append("(검증 조건에 null·빈 값 검사 → throw/return 이 있으면 참고). 검증 조건 = 읽은 값(변수)을 참조하는 같은 메서드 안의 조건식.\n\n");
    }

    private static String required(RequestSpecEntry e) {
        if (e.required.isEmpty()) {
            return "확인 불가";
        }
        return String.join(", ", e.required.stream().map(b -> b ? "Y" : "N").toList()) + " (어노테이션)";
    }

    private static List<String> validations(AnalysisResult r, RequestSpecEntry e) {
        Set<String> out = new LinkedHashSet<>();
        e.parameterIds.forEach(id -> AnalysisReportWriter.validations(r, r.parameter(id)).forEach(v -> out.add(md(v))));
        return new ArrayList<>(out);
    }

    /** 저장 위치: 이 값을 출처로 쓰는 바인딩의 객체.필드 (조건부면 표시) */
    private static List<String> stored(AnalysisResult r, RequestSpecEntry e) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : e.bindingIds) {
            Binding b = r.binding(id);
            ObjectInfo o = r.object(b.objectId);
            out.add("`" + o.className + "." + ("MAP".equals(o.kind) ? Labels.mapKey(b.field) : b.field) + "`"
                    + (b.conditional() ? " (조건부)" : ""));
        }
        return new ArrayList<>(out);
    }

    private static String readAt(AnalysisResult r, RequestParameterInfo p) {
        MethodInfo m = r.method(p.sourceMethodId);
        return r.classOf(m).name + "." + m.name + ":" + p.location.beginLine() + " (" + p.id + ")";
    }

    // ------------------------------------------------------------------ 3

    private void details(AnalysisResult r, StringBuilder sb) {
        if (r.requestSpec.isEmpty()) {
            return;
        }
        sb.append("### 1.3 파라미터 상세\n\n");
        for (RequestSpecEntry e : r.requestSpec) {
            sb.append("#### ").append(e.name).append(" (").append(e.id).append(")\n\n");
            sb.append("| 항목 | 내용 |\n|---|---|\n");
            for (String pid : e.parameterIds) {
                RequestParameterInfo p = r.parameter(pid);
                CallSiteInfo s = p.callSiteId == null ? null : r.callSite(p.callSiteId);
                String code = s == null ? null : (s.statement != null ? s.statement.trim() : s.expression);
                sb.append("| 읽기 ").append(p.id).append(" | ").append(md(readAt(r, p))).append(" · `").append(md(p.extraction)).append('`')
                        .append(code == null ? "" : "<br>`" + md(code) + "`")
                        .append(p.variable == null ? "" : "<br>받는 변수 `" + md(p.variable) + "`")
                        .append(p.readerLogic == null ? "" : "<br>읽기 함수에 로직 있음: " + md(p.readerLogic))
                        .append(s == null || !s.conditional ? "" : "<br>조건부 읽기: " + md(String.join(" && ", s.conditions)))
                        .append(" |\n");
            }
            sb.append("| 타입 | ").append(e.types.isEmpty() ? "확인 불가" : md(String.join(", ", e.types))).append(" |\n");
            sb.append("| 필수 | ").append(required(e)).append(" |\n");
            sb.append("| 기본값 | ").append(e.defaultValues.isEmpty() ? "-" : md(String.join(", ", e.defaultValues))).append(" |\n");
            sb.append("| 검증 조건 | ").append(join(validations(r, e))).append(" |\n");
            List<String> flows = new ArrayList<>();
            for (String id : e.bindingIds) {
                Binding b = r.binding(id);
                ObjectInfo o = r.object(b.objectId);
                flows.add("`" + o.className + "." + ("MAP".equals(o.kind) ? Labels.mapKey(b.field) : b.field) + " = "
                        + md(b.valueExpression) + "`" + (b.transforms.isEmpty() ? "" : " · 변환 " + md(Labels.bindingTransform(b)))
                        + (b.conditional() ? " · 조건 " + md(Labels.bindingCondition(b)) : "") + " (" + b.id + ")");
            }
            sb.append("| 저장 (바인딩) | ").append(join(flows)).append(" |\n");
            sb.append("| 사용 (호출 인자) | ").append(join(uses(r, e))).append(" |\n\n");
        }
    }

    /**
     * 요청 값이 그대로 들어가는 호출 인자: 받는 변수가 인자에 나오는 같은 메서드의 호출, 저장된 객체 필드(pm.appId)가
     * 인자에 나오는 객체 소유 메서드의 호출. 이름이 같은 다른 변수일 수 있어 원문 비교 결과임을 표에 남긴다.
     */
    private static List<String> uses(AnalysisResult r, RequestSpecEntry e) {
        Set<String> out = new LinkedHashSet<>();
        for (String pid : e.parameterIds) {
            RequestParameterInfo p = r.parameter(pid);
            if (p.variable != null && p.variable.matches("[A-Za-z_$][\\w$]*")) {
                usesIn(r, p.sourceMethodId, p.variable, p.callSiteId, out);
            }
        }
        for (String id : e.bindingIds) {
            Binding b = r.binding(id);
            ObjectInfo o = r.object(b.objectId);
            if (o.variable != null && o.methodId != null && !"MAP".equals(o.kind) && o.variable.matches("[A-Za-z_$][\\w$]*")) {
                usesIn(r, o.methodId, o.variable + "." + b.field, null, out);
            }
        }
        return new ArrayList<>(out);
    }

    private static void usesIn(AnalysisResult r, String methodId, String name, String self, Set<String> out) {
        Pattern ref = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$(])");
        for (CallSiteInfo s : r.callSitesOf(methodId)) {
            if (s.id.equals(self) || !r.parametersAt(s.id).isEmpty()) {
                continue;
            }
            if (s.arguments.stream().anyMatch(a -> ref.matcher(a).find())) {
                out.add("`" + md(s.expression) + "` (" + s.id + " :" + s.location.beginLine() + ")");
            }
        }
    }

    // ------------------------------------------------------------------ 4

    /** 요청 값이 하나라도 들어간 객체 (요청 셋업 클래스 ParamMgr · VO · Map) */
    private static List<ObjectInfo> holderObjects(AnalysisResult r) {
        return r.objects.stream().filter(o -> r.bindingsOf(o).stream()
                .anyMatch(b -> b.sources.stream().anyMatch(s -> "REQUEST".equals(s.kind())))).toList();
    }

    private void holders(AnalysisResult r, StringBuilder sb) {
        List<ObjectInfo> objs = holderObjects(r);
        sb.append("### 1.4 요청 값을 담는 객체\n\n");
        if (objs.isEmpty()) {
            sb.append("요청 값을 필드에 넣는 객체 없음.\n\n");
            return;
        }
        sb.append("요청 값이 하나라도 들어가는 객체(요청 셋업 클래스 `XxxAction.ParamMgr.setup(request)`·VO·Map)의 필드 값 설정 전체입니다. ")
                .append("굵게 = 요청 값. 요청 값이 아닌 출처(`call:` 등) 중에도 요청을 읽는 함수가 있을 수 있습니다 — 1.5 참고.\n\n");
        for (ObjectInfo o : objs) {
            sb.append("#### `").append(o.className).append('`').append(o.variable == null ? "" : " (" + o.variable + ")")
                    .append(o.methodId == null ? "" : " — " + MappingReportWriter.method(r, o.methodId)).append(" · ").append(o.id).append("\n\n");
            sb.append("| 필드 | 값 출처 | 변환 | 조건 | 값 식 | 위치 |\n|---|---|---|---|---|---|\n");
            for (Binding b : r.bindingsOf(o)) {
                MethodInfo m = r.method(b.methodId);
                boolean req = b.sources.stream().anyMatch(s -> "REQUEST".equals(s.kind()));
                sb.append("| `").append("MAP".equals(o.kind) ? Labels.mapKey(b.field) : b.field).append("` | ")
                        .append(req ? "**" + md(Labels.bindingSource(b)) + "**" : md(Labels.bindingSource(b))).append(" | ")
                        .append(md(Labels.bindingTransform(b))).append(" | ")
                        .append(md(Labels.bindingCondition(b))).append(" | `").append(md(b.valueExpression)).append("` | ")
                        .append(r.classOf(m).name).append('.').append(m.name).append(':').append(b.location.beginLine())
                        .append(" (").append(b.id).append(") |\n");
            }
            if (!o.unassignedFields.isEmpty()) {
                sb.append("\n미할당 필드(이 분석 범위에서 값을 넣는 코드 없음): ")
                        .append(String.join(", ", o.unassignedFields.stream().map(f -> "`" + f + "`").toList())).append('\n');
            }
            sb.append('\n');
        }
    }

    // ------------------------------------------------------------------ 5

    private void manual(AnalysisResult r, StringBuilder sb) {
        sb.append("### 1.5 수동 확인 필요\n\n");
        boolean any = false;
        List<RequestParameterInfo> unknown = r.parameters.stream().filter(p -> "UNKNOWN".equals(p.nameResolution)).toList();
        if (!unknown.isEmpty()) {
            any = true;
            sb.append("**이름을 정적으로 확정하지 못한 읽기**\n\n");
            unknown.forEach(p -> sb.append("- ").append(p.id).append(" `").append(md(p.nameExpression)).append("` — ")
                    .append(p.extraction).append(", ").append(readAt(r, p)).append('\n'));
            sb.append('\n');
        }
        List<RequestParameterInfo> bulk = r.parameters.stream().filter(p -> !p.named() && !"UNKNOWN".equals(p.nameResolution)).toList();
        if (!bulk.isEmpty()) {
            any = true;
            sb.append("**전체 조회 / Body / 객체 바인딩** (개별 파라미터를 알 수 없음)\n\n");
            bulk.forEach(p -> sb.append("- ").append(p.id).append(' ').append(Labels.parameterName(p)).append(" — ")
                    .append(p.extraction).append(p.variable == null ? "" : " → `" + p.variable + "`")
                    .append(", ").append(readAt(r, p)).append('\n'));
            sb.append('\n');
        }
        List<String> passed = requestPassedUnanalyzed(r);
        if (!passed.isEmpty()) {
            any = true;
            sb.append("**요청 객체를 넘겼지만 본문을 분석하지 않은 호출** (그 안에서 더 읽을 수 있음 — 필요하면 `analysis.expand-classes` 에 추가)\n\n");
            passed.forEach(x -> sb.append("- ").append(x).append('\n'));
            sb.append('\n');
        }
        if (!any) {
            sb.append("없음.\n\n");
        }
        sb.append("> 범위: `HttpServletRequest.getParameter/Values/Map`, Spring 어노테이션, 설정 `request-parameter-rules` 의 읽기 메서드. ")
                .append("헤더·쿠키·attribute 는 규칙에 넣은 경우만 포함됩니다.\n");
    }

    /** 요청 객체(…ServletRequest 타입 파라미터)를 인자로 넘긴 호출 중 본문을 분석하지 않았고 요청 읽기로 잡히지 않은 것 */
    private static List<String> requestPassedUnanalyzed(AnalysisResult r) {
        List<String> out = new ArrayList<>();
        for (CallSiteInfo s : r.callSites) {
            if (!r.parametersAt(s.id).isEmpty() || s.expansion == Expansion.EXPANDED || s.expansion == Expansion.SHARED
                    || s.expansion == Expansion.CYCLE) {
                continue;
            }
            MethodInfo caller = r.method(s.callerMethodId);
            Set<String> reqNames = new LinkedHashSet<>();
            for (MethodParameter p : caller.parameters) {
                if (p.type() != null && p.type().endsWith("ServletRequest")) {
                    reqNames.add(p.name());
                }
            }
            if (reqNames.isEmpty() || s.arguments.stream().noneMatch(reqNames::contains)) {
                continue;
            }
            out.add("`" + md(s.expression) + "` — " + r.classOf(caller).name + "." + caller.name + ":" + s.location.beginLine()
                    + " (" + s.id + ", " + (s.expansion == null ? "?" : s.expansion.name()) + ")");
        }
        return out;
    }

    private static String join(List<String> xs) {
        return xs.isEmpty() ? "-" : String.join("<br>", xs); // 항목마다 md() 를 이미 거침
    }
}
