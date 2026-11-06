package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ClassRole;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;
import analyzer.model.Origin;
import analyzer.model.RequestParameterInfo;
import analyzer.model.RequestSpecEntry;
import analyzer.model.ResolutionStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static analyzer.serializer.MappingReportWriter.md;
import static analyzer.serializer.MappingReportWriter.method;

/** analysis.md — 전체 소스 분석서 (공통 모델에서만 생성). */
public final class AnalysisReportWriter {

    public String render(AnalysisResult r, String treeText, String mermaidText, String mermaidStatus) {
        StringBuilder sb = new StringBuilder();
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        sb.append("# 소스 분석서: ").append(target.name).append('.').append(entry.name).append("()\n\n");
        sb.append("> `analysis.yaml`(원본 데이터)에서 생성. ID(C*, M*, CALL*, 블록, P*, O*, B*)는 모든 파일에서 동일합니다. ")
                .append("필드 매핑 전체는 `mapping.md` 에 있습니다.\n\n");

        overview(r, target, entry, sb);
        sb.append("## 2. 실행 흐름도\n\n");
        sb.append("조건문·try/catch·주요 메서드 호출 중심이며, 객체 바인딩은 객체당 요약 노드 1개로 표시합니다(`flowchart.mmd`).\n\n");
        sb.append("```mermaid\n").append(mermaidText == null ? "" : mermaidText).append("```\n\n");
        requestParameters(r, sb);
        bindings(r, sb);
        calls(r, sb);
        exceptions(r, sb);
        appendix(r, treeText, mermaidStatus, sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 1

    private void overview(AnalysisResult r, ClassInfo target, MethodInfo entry, StringBuilder sb) {
        sb.append("## 1. 분석 개요\n\n| 항목 | 값 |\n|---|---|\n");
        sb.append("| 대상 클래스 | `").append(target.qualifiedName).append("` (").append(target.id).append(", ")
                .append(target.role.label()).append(") |\n");
        sb.append("| 대상 메서드 | `").append(entry.signature).append("` (").append(entry.id).append(", ")
                .append(r.entrySelection).append(") |\n");
        sb.append("| 분석 Depth | max-depth ").append(r.settings.get("max-depth"))
                .append(Boolean.TRUE.equals(r.settings.get("expand-same-class-methods")) ? " · 같은 클래스 메서드 전개" : "");
        Object roles = r.settings.get("expand-roles");
        if (roles instanceof List<?> l && !l.isEmpty()) {
            sb.append(" · 역할 전개 ").append(l);
        }
        sb.append(" (실제 최대 D").append(r.callSites.stream().mapToInt(s -> s.depth).max().orElse(0)).append(") |\n");
        sb.append("| 입력 파라미터 | ").append(entry.parameters.isEmpty() ? "-" : String.join(", ",
                entry.parameters.stream().map(p -> "`" + p.type() + " " + p.name() + "`").toList())).append(" |\n");
        sb.append("| 반환 타입 | `").append(entry.returnType).append("` |\n");
        sb.append("| 선언 예외 | ").append(entry.thrownTypes.isEmpty() ? "-" : String.join(", ", entry.thrownTypes)).append(" |\n");
        sb.append("| 소스 위치 | `").append(entry.location.file()).append(":").append(entry.location.beginLine())
                .append("-").append(entry.location.endLine()).append("` |\n\n");
        sb.append("**관련 클래스** (진입 메서드에서 도달한 source 클래스)\n\n| ID | 클래스 | 역할 | 근거 |\n|---|---|---|---|\n");
        for (ClassInfo c : r.classes()) {
            if (c.origin != Origin.SOURCE) {
                continue;
            }
            String ev = String.join(", ", c.roleEvidence.stream().map(e -> e.kind() + ":" + e.value()).toList());
            sb.append("| ").append(c.id).append(" | `").append(c.name).append("` | ").append(c.role.label())
                    .append(c.roleConflict ? " ⚠️충돌" : "").append(" | ").append(ev.isEmpty() ? "-" : ev).append(" |\n");
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------ 3

    private void requestParameters(AnalysisResult r, StringBuilder sb) {
        sb.append("## 3. Request Parameter 분석\n\n");
        if (r.requestSpec.isEmpty()) {
            sb.append("이름이 확인된 요청 파라미터 없음.\n");
        } else {
            sb.append("| Parameter | Source | Type | Default | Validation | 바인딩 |\n|---|---|---|---|---|---|\n");
            for (RequestSpecEntry e : r.requestSpec) {
                List<String> src = new ArrayList<>();
                Set<String> validation = new LinkedHashSet<>();
                for (String pid : e.parameterIds) {
                    RequestParameterInfo p = r.parameter(pid);
                    src.add(p.extraction + " · " + r.method(p.sourceMethodId).name + ":" + p.location.beginLine() + " (" + p.id + ")");
                    validation.addAll(validations(r, p));
                }
                List<String> bound = e.bindingIds.stream().map(id -> {
                    var b = r.binding(id);
                    var o = r.object(b.objectId);
                    return "`" + o.className + "." + b.field + "`";
                }).distinct().toList();
                sb.append("| **`").append(e.name).append("`** | ").append(md(String.join("<br>", src)))
                        .append(" | ").append(e.types.isEmpty() ? "-" : String.join(", ", e.types))
                        .append(" | ").append(e.defaultValues.isEmpty() ? "-" : md(String.join(", ", e.defaultValues)))
                        .append(e.required.isEmpty() ? "" : " (required " + e.required + ")")
                        .append(" | ").append(validation.isEmpty() ? "-" : md(String.join("<br>", validation)))
                        .append(" | ").append(bound.isEmpty() ? "-" : String.join(", ", bound)).append(" |\n");
            }
            sb.append("\n> Validation: 읽은 값(변수)을 참조하는 같은 메서드 안의 조건식입니다. 본문을 분석하지 않은 메서드(depth-limit) 안의 검증은 포함되지 않습니다.\n");
        }
        List<RequestParameterInfo> unknown = r.parameters.stream().filter(p -> "UNKNOWN".equals(p.nameResolution)).toList();
        List<RequestParameterInfo> bulk = r.parameters.stream().filter(p -> !p.named() && !"UNKNOWN".equals(p.nameResolution)).toList();
        if (!unknown.isEmpty()) {
            sb.append("\n**이름을 정적으로 확정하지 못한 읽기** (수동 확인 필요)\n\n");
            unknown.forEach(p -> sb.append("- ").append(p.id).append(" `").append(md(p.nameExpression)).append("` — ")
                    .append(p.extraction).append(", ").append(p.location.shortText()).append('\n'));
        }
        if (!bulk.isEmpty()) {
            sb.append("\n**전체 조회 / Body / 객체 바인딩** (개별 파라미터를 만들지 않음)\n\n");
            bulk.forEach(p -> sb.append("- ").append(p.id).append(' ').append(Labels.parameterName(p)).append(" — ")
                    .append(p.extraction).append(p.variable == null ? "" : " → `" + p.variable + "`")
                    .append(", ").append(p.location.shortText()).append('\n'));
        }
        sb.append('\n');
    }

    /** 읽은 값을 참조하는 조건식: 변수명을 포함하는 if/else-if/while/switch, 또는 조건식 안에서 바로 읽은 경우 */
    private static List<String> validations(AnalysisResult r, RequestParameterInfo p) {
        List<String> out = new ArrayList<>();
        if (p.callSiteId != null) {
            CallSiteInfo s = r.callSite(p.callSiteId);
            if (s.controlBlockId != null && ("CONDITION".equals(s.position) || "SELECTOR".equals(s.position))) {
                out.add(Labels.blockHeader(r.block(s.controlBlockId)));
            }
        }
        if (p.variable == null || !p.variable.matches("[A-Za-z_]\\w*")) {
            return out;
        }
        Pattern var = Pattern.compile("\\b" + Pattern.quote(p.variable) + "\\b");
        for (ControlBlock b : r.blocksOf(p.sourceMethodId)) {
            if (b.condition != null && var.matcher(b.condition).find()
                    && (b.type == BlockType.IF || b.type == BlockType.ELSE_IF || b.type == BlockType.WHILE
                    || b.type == BlockType.SWITCH)) {
                out.add(Labels.blockHeader(b) + exitOf(r, b));
            }
        }
        return out;
    }

    /** 조건 블록 본문이 바로 throw/return 하면 표시 */
    private static String exitOf(AnalysisResult r, ControlBlock b) {
        for (String cid : b.childBlockIds) {
            ControlBlock c = r.block(cid);
            if (c.type == BlockType.THROW) {
                return " → throw";
            }
            if (c.type == BlockType.RETURN) {
                return " → return";
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ 4

    private void bindings(AnalysisResult r, StringBuilder sb) {
        sb.append("## 4. VO / DTO / Command 바인딩 명세\n\n");
        if (r.bindings.isEmpty()) {
            sb.append(Boolean.FALSE.equals(r.settings.get("include-bindings")) ? "`include-bindings: false` — 수집하지 않음.\n\n"
                    : "바인딩 없음.\n\n");
            return;
        }
        sb.append("전체 매핑입니다(생략 없음). 객체별 필드 커버리지·다중 할당 최종 값은 `mapping.md` 를 참고하세요.\n\n");
        List<analyzer.model.Binding> all = new ArrayList<>();
        r.objects.forEach(o -> all.addAll(r.bindingsOf(o)));
        sb.append(MappingReportWriter.bindingTable(r, all, true)).append('\n');
    }

    // ------------------------------------------------------------------ 5

    private void calls(AnalysisResult r, StringBuilder sb) {
        sb.append("## 5. 메서드 호출 관계\n\n");
        sb.append("source 메서드 호출입니다(VO getter/setter 와 외부 라이브러리 호출 제외 — 전체는 `analysis.yaml` calls).\n\n");
        sb.append("| ID | Caller | Callee | Arguments | Return | Depth · 전개 |\n|---|---|---|---|---|---|\n");
        for (CallSiteInfo s : r.callSites) {
            if (s.targetMethodId == null) {
                continue;
            }
            MethodInfo t = r.method(s.targetMethodId);
            ClassInfo tc = r.classOf(t);
            if (t.origin != Origin.SOURCE || tc.role == ClassRole.VO) {
                continue;
            }
            String impl = s.implementationMethodId == null ? "" : "<br>⇒ 구현 후보 `" + Labels.implementationTarget(r, s) + "`";
            sb.append("| ").append(s.id).append(" | ").append(method(r, s.callerMethodId))
                    .append(" | `").append(tc.name).append('.').append(md(t.signature)).append("` [").append(tc.role.label()).append("]").append(impl)
                    .append(" | ").append(s.arguments.isEmpty() ? "-" : "`" + md(String.join(", ", s.arguments)) + "`")
                    .append(" | `").append(md(t.returnType)).append("` → ").append(n(s.resultUsage))
                    .append(" | D").append(s.depth).append(" · ").append(s.expansion.name().toLowerCase().replace('_', '-'))
                    .append(" |\n");
        }
        if (r.excludedExternalCalls > 0) {
            sb.append("\n- `include-external-library-calls: false` 로 제외된 외부 라이브러리 호출: ").append(r.excludedExternalCalls).append("건\n");
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------ 6

    private static final Pattern NEW_TYPE = Pattern.compile("^new\\s+([\\w.]+)");

    private void exceptions(AnalysisResult r, StringBuilder sb) {
        sb.append("## 6. 예외 처리\n\n");
        sb.append("| Exception | 발생 가능 위치 | 처리 방식 | 반환 결과 |\n|---|---|---|---|\n");
        boolean any = false;
        for (ControlBlock c : r.blocks) {
            if (c.type != BlockType.CATCH) {
                continue;
            }
            any = true;
            ControlBlock t = r.block(c.tryBlockId);
            List<String> where = new ArrayList<>();
            where.add("try " + t.id + " 본문 (" + r.method(t.methodId).name + " " + t.location.file() + ":"
                    + t.location.beginLine() + "-" + t.location.endLine() + ")");
            r.blocks.stream().filter(x -> x.type == BlockType.THROW && c.id.equals(x.caughtBy))
                    .forEach(x -> where.add(x.id + " `throw " + Labels.truncate(x.expression, 50) + "` :" + x.location.beginLine()));
            List<String> how = new ArrayList<>();
            if (c.rethrows) {
                how.add("재발생(rethrow)");
            }
            if (c.throwsNewException) {
                how.add("새 예외 발생");
            }
            c.logCalls.forEach(x -> how.add("로그: `" + Labels.truncate(x, 50) + "`"));
            c.errorResponseCalls.forEach(x -> how.add("오류 응답: `" + Labels.truncate(x, 60) + "`"));
            c.handlerCalls.stream().filter(x -> !c.logCalls.contains(x) && !c.errorResponseCalls.contains(x))
                    .forEach(x -> how.add("호출: `" + Labels.truncate(x, 50) + "`"));
            if (how.isEmpty()) {
                how.add("처리 없음(무시)");
            }
            List<String> result = new ArrayList<>();
            descendants(r, c).forEach(x -> {
                if (x.type == BlockType.RETURN) {
                    result.add("`return " + n(x.expression) + "`");
                } else if (x.type == BlockType.THROW) {
                    result.add("`throw " + Labels.truncate(x.expression, 40) + "` → "
                            + (x.caughtBy != null ? x.caughtBy : Boolean.TRUE.equals(x.propagatesToCaller) ? "호출자" : "unresolved"));
                }
            });
            if (result.isEmpty()) {
                result.add("try 이후 정상 흐름 계속" + (t.finallyBlockId == null ? "" : " (finally " + t.finallyBlockId + " 실행)"));
            }
            sb.append("| ").append(c.id).append(" `").append(String.join(" \\| ", c.exceptionTypes)).append("` | ")
                    .append(md(String.join("<br>", where)).replace("\\|", "|")).append(" | ").append(String.join("<br>", how).replace("|", "\\|"))
                    .append(" | ").append(String.join("<br>", result).replace("|", "\\|")).append(" |\n");
        }
        for (ControlBlock b : r.blocks) {
            if (b.type != BlockType.THROW || !Boolean.TRUE.equals(b.propagatesToCaller)) {
                continue;
            }
            any = true;
            Matcher mt = NEW_TYPE.matcher(b.expression == null ? "" : b.expression);
            sb.append("| ").append(b.id).append(" `").append(mt.find() ? mt.group(1) : md(Labels.truncate(b.expression, 40)))
                    .append("` | `").append(md(Labels.truncate("throw " + b.expression, 60))).append("` ")
                    .append(r.method(b.methodId).name).append(" ").append(b.location.shortText())
                    .append(" | 잡히지 않음 → 호출자로 전파").append(b.executesFinally.isEmpty() ? "" : " (finally " + b.executesFinally + " 경유)")
                    .append(" | - |\n");
        }
        if (!any) {
            sb.append("| - | - | 예외 처리 블록 없음 | - |\n");
        }
        sb.append("\n> 호출된 메서드 내부에서 발생할 수 있는 예외 타입은 정적으로 확정하지 않으므로 특정 호출을 catch 로 연결하지 않습니다(`throw` 문만 매칭). ")
                .append("HTTP 상태는 소스의 `setStatus`/`sendError` 호출이 있을 때만 처리 방식에 나타납니다.\n\n");
    }

    private static List<ControlBlock> descendants(AnalysisResult r, ControlBlock b) {
        List<ControlBlock> out = new ArrayList<>();
        for (String id : b.childBlockIds) {
            ControlBlock c = r.block(id);
            out.add(c);
            out.addAll(descendants(r, c));
        }
        return out;
    }

    // ------------------------------------------------------------------ 7/8

    private void appendix(AnalysisResult r, String treeText, String mermaidStatus, StringBuilder sb) {
        sb.append("## 7. 마이그레이션 비교 결과\n\n신규 프로젝트 분석 데이터가 없어 생성하지 않았습니다(`comparison.md` 미생성).\n\n");
        sb.append("## 8. 부록\n\n### 8.1 분석 설정\n\n| 설정 | 값 |\n|---|---|\n");
        r.settings.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));

        sb.append("\n### 8.2 메서드 목록\n\n| ID | 메서드 | 접근 | static | Depth | 분석 | 위치 |\n|---|---|---|---|---|---|---|\n");
        for (MethodInfo m : r.methods()) {
            sb.append("| ").append(m.id).append(" | `").append(r.classOf(m).name).append('.').append(md(m.signature))
                    .append("` | ").append(m.visibility.text()).append(m.modifiersImplicit ? " (암묵적)" : "")
                    .append(" | ").append(m.isStatic ? "Y" : "").append(" | D").append(m.depth)
                    .append(" | ").append(m.analyzed ? "전개" : m.notAnalyzedReason.name())
                    .append(" | ").append(m.location == null ? "-" : m.location.shortText()).append(" |\n");
        }

        sb.append("\n### 8.3 Unresolved 목록\n\n");
        List<CallSiteInfo> requestReads = r.callSites.stream()
                .filter(s -> s.resolution != ResolutionStatus.RESOLVED && !r.parametersAt(s.id).isEmpty()).toList();
        List<CallSiteInfo> unresolved = r.callSites.stream()
                .filter(s -> s.resolution != ResolutionStatus.RESOLVED && r.parametersAt(s.id).isEmpty()).toList();
        List<ControlBlock> unresolvedThrows = r.blocks.stream()
                .filter(b -> b.type == BlockType.THROW && b.exceptionResolution == ResolutionStatus.UNRESOLVED).toList();
        List<analyzer.model.Binding> unresolvedBindings = r.bindings.stream()
                .filter(b -> b.resolution != ResolutionStatus.RESOLVED).toList();
        if (unresolved.isEmpty() && unresolvedThrows.isEmpty() && unresolvedBindings.isEmpty()) {
            sb.append("없음.\n");
        } else {
            sb.append("| ID | 대상 | 상태 | 사유 | 위치 |\n|---|---|---|---|---|\n");
            for (CallSiteInfo s : unresolved) {
                sb.append("| ").append(s.id).append(" | `").append(md(Labels.truncate(s.expression, 60))).append("` | ")
                        .append(s.resolution).append(" | ").append(md(n(s.resolutionReason))).append(" | ")
                        .append(s.location.shortText()).append(" |\n");
            }
            for (ControlBlock b : unresolvedThrows) {
                sb.append("| ").append(b.id).append(" | `throw ").append(md(Labels.truncate(b.expression, 50)))
                        .append("` | 예외 흐름 UNRESOLVED | ").append(md(b.exceptionReason)).append(" | ")
                        .append(b.location.shortText()).append(" |\n");
            }
            for (analyzer.model.Binding b : unresolvedBindings) {
                sb.append("| ").append(b.id).append(" | `").append(r.object(b.objectId).className).append('.')
                        .append(md(b.field)).append("` 바인딩 | ").append(b.resolution).append(" | ")
                        .append(md(n(b.resolutionReason))).append(" | ").append(b.location.shortText()).append(" |\n");
            }
        }
        if (!requestReads.isEmpty()) {
            sb.append("\n- Request 읽기 호출 ").append(requestReads.size())
                    .append("건은 수신 타입(HttpServletRequest 등)이 source-root 밖이라 PARTIALLY_RESOLVED — 3장에 표시\n");
        }
        sb.append("- 깊이 제한으로 전개하지 않은 호출(미해결과 구분): ")
                .append(r.callSites.stream().filter(s -> s.expansion == Expansion.DEPTH_LIMIT).count()).append("건\n");

        sb.append("\n### 8.4 호출 트리\n\n```text\n").append(treeText).append("```\n");

        sb.append("\n### 8.5 분석 범위와 정적 분석 한계\n\n");
        sb.append("- 진입 메서드에서 도달한 메서드만 포함하며, 타입 해석을 위해 참조만 한 파일은 포함하지 않습니다.\n");
        sb.append("- 다른 클래스 메서드는 max-depth 까지 호출 관계·인자만 기록하고, `expand-roles` 역할만 본문을 분석합니다.\n");
        if (Boolean.TRUE.equals(r.settings.get("link-single-implementation"))) {
            sb.append("- 인터페이스 호출은 선언 대상을 기록하고, source-root 안 구현이 정확히 1개일 때만 구현 후보로 연결합니다(런타임 DI 확정 아님).\n");
        }
        sb.append("- 바인딩: setter·builder·생성자·직접 필드 대입·Map.put·copyProperties 를 수집합니다. 값 출처는 인자 → 지역 변수(초기화·대입) → ")
                .append("메서드 파라미터(분석된 호출 위치의 인자, via) 순으로 추적하고, getter·외부 호출의 반환값 내부는 추적하지 않습니다(`call:`).\n");
        sb.append("- 객체 정체성: 메서드 인자로 넘긴 객체는 호출 위치가 1개이고 인자가 변수일 때만 같은 객체로 잇습니다.\n");
        sb.append("- Lombok 등 생성 코드(@Builder, @AllArgsConstructor, @Setter)는 소스에 없으므로 메서드명/인자 위치로만 기록하고 resolution 을 낮춥니다. ")
                .append("Reflection, 프레임워크 자동 바인딩(@ModelAttribute, ActionForm populate), copyProperties 의 필드별 매핑은 정적으로 확인하지 않습니다.\n");
        sb.append("- 호출 순서는 Java 평가 순서(수신 객체 → 인자 → 호출), for 는 init → 조건 → 본문 → update. switch fall-through, 생성자 본문, 익명/로컬 클래스는 흐름에서 제외됩니다.\n");
        sb.append("- Mermaid 렌더링 검증: ").append(mermaidStatus).append('\n');
        if (!r.notes.isEmpty()) {
            sb.append("\n### 8.6 분석 중 생략된 항목\n\n");
            r.notes.forEach(x -> sb.append("- ").append(x).append('\n'));
        }
    }

    private static String n(String s) {
        return s == null ? "-" : s;
    }
}
