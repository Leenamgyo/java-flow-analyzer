package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.ClassInfo;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.ResolutionStatus;
import analyzer.model.VariableChange;
import analyzer.model.VariableInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** mapping.md — VO / DTO / Command / Map 필드 매핑 명세 (전체 매핑을 생략하지 않는다). */
public final class MappingReportWriter {

    public String render(AnalysisResult r) {
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        StringBuilder sb = new StringBuilder();
        sb.append("# 데이터 바인딩 명세: ").append(target.name).append('.').append(entry.name).append("()\n\n");
        sb.append("> 원본 데이터: `analysis.yaml` 의 `objects`. 같은 필드에 여러 번 설정되면 모두 순서대로 기록합니다. ")
                .append("출처를 확정할 수 없으면 `unresolved` 로 남기며 추측하지 않습니다.\n\n");
        if (r.objects.isEmpty()) {
            sb.append("바인딩 대상 객체 없음.\n\n");
            variables(r, sb);
            return sb.toString();
        }
        sb.append("## 요약\n\n");
        sb.append("| Object | Class | Variable | 소유 메서드 | 할당 | 할당 필드 | 조건부 할당 | 변환 | 미해결 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (ObjectInfo o : r.objects) {
            List<Binding> bs = r.bindingsOf(o);
            sb.append("| ").append(o.id).append(" | `").append(o.className).append("` | `").append(o.variable).append("` | ")
                    .append(o.methodId == null ? "(필드)" : method(r, o.methodId)).append(" | ").append(bs.size())
                    .append(" | ").append(o.assignedFields.size()).append(" | ").append(bs.stream().filter(Binding::conditional).count())
                    .append(" | ").append(bs.stream().filter(b -> !b.transforms.isEmpty()).count())
                    .append(" | ").append(bs.stream().filter(b -> b.resolution != ResolutionStatus.RESOLVED).count()).append(" |\n");
        }
        for (ObjectInfo o : r.objects) {
            object(r, o, sb);
        }
        variables(r, sb);
        return sb.toString();
    }

    /**
     * 변수 상태 변화: 선언 뒤 값이 바뀌는 지역 변수·파라미터의 전체 이력 (선언 → 변경 …, 조건 포함).
     * 바인딩 값이 이런 변수면 "최종 값"은 실행 경로(조건)에 따라 달라진다.
     */
    private void variables(AnalysisResult r, StringBuilder sb) {
        sb.append("## 변수 상태 변화\n\n");
        // 값이 바뀌지 않는 지역 변수(선언만 — 흐름도·트리에 선언 코드를 보이려고 모델에 있음)는 표에서 뺀다
        List<VariableInfo> changed = r.variables.stream().filter(v -> Labels.changeCount(r, v) > 0).toList();
        if (changed.isEmpty()) {
            sb.append("선언 뒤 값이 바뀌는 변수 없음.\n");
            return;
        }
        sb.append("> 원본 데이터: `analysis.yaml` 의 `variables`. 선언(또는 파라미터) 뒤에 `=`·`+=`·`++` 등으로 값이 바뀌는 지역 변수만 대상입니다 ")
                .append("(필드·for 헤더 변수 제외). 이 변수가 VO 에 들어가면 바인딩 표에 `⟳` 로 표시됩니다.\n\n");
        sb.append("| Variable | 메서드 | 타입 | 변경 횟수 | 바인딩에 사용 |\n|---|---|---|---|---|\n");
        for (VariableInfo v : changed) {
            List<String> used = r.bindings.stream().filter(b -> b.methodId.equals(v.methodId) && v.name.equals(b.valueExpression))
                    .map(b -> b.id + " " + r.object(b.objectId).className + "." + b.field).toList();
            sb.append("| ").append(v.id).append(" `").append(v.name).append("` | ").append(method(r, v.methodId)).append(" | `")
                    .append(v.type).append("` | ").append(Labels.changeCount(r, v)).append(" | ")
                    .append(used.isEmpty() ? "-" : String.join(", ", used)).append(" |\n");
        }
        sb.append('\n');
        for (VariableInfo v : changed) {
            sb.append("### ").append(v.id).append(" `").append(v.name).append("` — ").append(method(r, v.methodId)).append("\n\n");
            sb.append("| # | 변경 | 코드 | 출처 | 조건 | 위치 |\n|---|---|---|---|---|---|\n");
            for (String cid : v.changeIds) {
                VariableChange c = r.variableChange(cid);
                String op = switch (c.op) {
                    case "INIT" -> c.valueExpression == null ? "선언(값 없음)" : "선언";
                    case "PARAM" -> "파라미터";
                    case "ASSIGN" -> "대입 =";
                    case "COMPOUND" -> "복합 " + c.operator;
                    case "INCREMENT" -> "증가 ++";
                    default -> "감소 --";
                };
                sb.append("| ").append(c.id).append(" | ").append(op).append(" | `").append(c.expression.replace("|", "\\|"))
                        .append("` | ").append(c.sources.isEmpty() ? "-" : String.join(" + ",
                                c.sources.stream().map(x -> x.display().replace("|", "\\|")).toList()))
                        .append(" | ").append(c.conditions.isEmpty() ? "always" : "`" + String.join(" && ", c.conditions).replace("|", "\\|") + "`")
                        .append(" | ").append(c.location.shortText()).append(" |\n");
            }
            sb.append('\n');
        }
    }

    private void object(AnalysisResult r, ObjectInfo o, StringBuilder sb) {
        List<Binding> bs = r.bindingsOf(o);
        sb.append("\n## ").append(o.id).append(" `").append(o.className).append("` (").append(o.variable).append(")");
        if (o.methodId != null) {
            sb.append(" — ").append(method(r, o.methodId));
        }
        sb.append("\n\n");
        if ("VO".equals(o.kind)) {
            sb.append("### 필드 커버리지\n\n| 구분 | 개수 | 필드 |\n|---|---|---|\n");
            row(sb, "선언된 필드", o.declaredFields);
            row(sb, "실제 할당된 필드", o.assignedFields);
            row(sb, "조건부로만 할당된 필드", o.conditionalFields);
            row(sb, "초기값만 존재하는 필드", o.initialOnlyFields);
            row(sb, "할당되지 않은 필드", o.unassignedFields);
            row(sb, "매핑 여부 확인 불가", o.unknownFields);
            sb.append("\n> 이 분석 범위에서 할당되지 않았다는 사실만으로 마이그레이션 누락으로 판정하지 않습니다.\n\n");
        }
        sb.append("### 바인딩\n\n");
        sb.append(bindingTable(r, bs, false));
        Map<String, List<Binding>> byField = new LinkedHashMap<>();
        bs.forEach(b -> byField.computeIfAbsent(b.field, k -> new ArrayList<>()).add(b));
        List<Map.Entry<String, List<Binding>>> multi = byField.entrySet().stream().filter(e -> e.getValue().size() > 1).toList();
        if (!multi.isEmpty()) {
            sb.append("\n### 여러 번 할당되는 필드의 최종 값\n\n");
            for (var e : multi) {
                finalValue(r, e.getKey(), e.getValue(), sb);
            }
        }
    }

    /** Object | Field | Source | Transform | Condition | Default | Assignment | Location | Resolution */
    static String bindingTable(AnalysisResult r, List<Binding> bs, boolean withObject) {
        StringBuilder sb = new StringBuilder();
        sb.append(withObject ? "| Object " : "| # ").append("| Field | Source | Transform | Condition | Default | Assignment | Location | Resolution |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Binding b : bs) {
            ObjectInfo o = r.object(b.objectId);
            sb.append("| ").append(withObject ? "`" + o.className + "` (" + o.variable + ")" : b.id)
                    .append(" | `").append(md(b.field)).append("` | ").append(md(Labels.bindingSource(b)))
                    .append(" | ").append(md(Labels.bindingTransform(b))).append(" | ").append(md(Labels.bindingCondition(b)))
                    .append(" | ").append(b.defaultValue == null ? "-" : "`" + md(b.defaultValue) + "`")
                    .append(" | ").append(b.assignment.toLowerCase()).append(b.method.equals("=") ? "" : " `" + md(b.method) + "`")
                    .append(" | ").append(r.method(b.methodId).name).append(" · ").append(b.location.shortText())
                    .append(" | ").append(b.resolution == ResolutionStatus.RESOLVED ? "resolved"
                            : b.resolution.name().toLowerCase() + (b.resolutionReason == null ? "" : ": " + md(b.resolutionReason)))
                    .append(b.reassigned ? " ⚠️ 출처 변수 재대입" : "").append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 순서대로 평가: 마지막 무조건 할당 이후의 조건부 할당은 조건별 값으로, 그 외에는 마지막 무조건 할당 값.
     * 마지막 무조건 할당 이전의 할당은 덮어써진다.
     */
    private void finalValue(AnalysisResult r, String field, List<Binding> bs, StringBuilder sb) {
        sb.append("- `").append(field).append("` (").append(String.join(" → ", bs.stream().map(b -> b.id).toList())).append(")\n");
        int lastAlways = -1;
        for (int i = 0; i < bs.size(); i++) {
            if (!bs.get(i).conditional()) {
                lastAlways = i;
            }
        }
        for (int i = bs.size() - 1; i > lastAlways; i--) {
            Binding b = bs.get(i);
            sb.append("  - `").append(md(Labels.bindingCondition(b))).append("` → ").append(md(Labels.bindingSource(b)))
                    .append(" (").append(b.id).append(")\n");
        }
        if (lastAlways >= 0) {
            Binding b = bs.get(lastAlways);
            sb.append("  - ").append(lastAlways == bs.size() - 1 ? "항상" : "그 외").append(" → ")
                    .append(md(Labels.bindingSource(b))).append(" (").append(b.id).append(")\n");
            if (lastAlways > 0) {
                sb.append("  - ").append(String.join(", ", bs.subList(0, lastAlways).stream().map(x -> x.id).toList()))
                        .append(" 는 이후 무조건 할당(").append(b.id).append(")으로 덮어써짐\n");
            }
        } else {
            sb.append("  - 그 외 → 이 분석 범위에서 할당 없음 (이전 값 유지)\n");
        }
        if (bs.stream().map(b -> b.methodId).distinct().count() > 1) {
            sb.append("  - ⚠️ 여러 메서드에 걸친 할당 — 순서는 분석 순서 기준이며 실제 호출 순서와 다를 수 있음\n");
        }
        if (bs.stream().anyMatch(b -> b.conditions.stream().anyMatch(c -> c.startsWith("loop:")))) {
            sb.append("  - ⚠️ 반복문 안의 할당 포함 — 반복 횟수에 따라 최종 값이 달라질 수 있음\n");
        }
    }

    private static void row(StringBuilder sb, String label, List<String> fields) {
        sb.append("| ").append(label).append(" | ").append(fields.size()).append(" | ")
                .append(fields.isEmpty() ? "-" : String.join(", ", fields.stream().map(f -> "`" + f + "`").toList())).append(" |\n");
    }

    static String method(AnalysisResult r, String methodId) {
        MethodInfo m = methodId == null ? null : r.method(methodId);
        if (m == null) {
            return "(필드)"; // 소유 메서드 없는 객체 (this·필드)
        }
        return "`" + Labels.methodName(r, m) + "` (" + m.id + ")";
    }

    static String md(String s) {
        return s == null ? "-" : s.replace("|", "\\|").replace("\n", " ");
    }
}
