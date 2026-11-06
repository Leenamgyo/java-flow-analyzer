package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
import analyzer.model.Binding;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.ResolutionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 트리·Mermaid 가 공유하는 표시 문자열 및 요소 정렬 규칙. */
public final class Labels {
    private Labels() {
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    /** 호출 대상 표시: 같은 클래스면 signature, 다른 클래스면 Class.signature, 미해결이면 원문 */
    public static String callTarget(AnalysisResult r, CallSiteInfo s) {
        if (s.targetMethodId == null) {
            return truncate(s.expression, 70);
        }
        MethodInfo t = r.method(s.targetMethodId);
        MethodInfo caller = r.method(s.callerMethodId);
        if (t.classId.equals(caller.classId)) {
            return t.signature;
        }
        return r.classOf(t).name + "." + t.signature;
    }

    /** 구현 후보 연결 대상 표시: Class.signature (연결이 없으면 null) */
    public static String implementationTarget(AnalysisResult r, CallSiteInfo s) {
        if (s.implementationMethodId == null) {
            return null;
        }
        MethodInfo impl = r.method(s.implementationMethodId);
        return r.classOf(impl).name + "." + impl.signature + " [" + r.classOf(impl).role.label() + "]";
    }

    /** 구현 탐색은 했지만 연결하지 않은 사유의 짧은 표시 (해당 없으면 null) */
    public static String implementationSkipped(CallSiteInfo s) {
        if (s.implementationBasis == null || s.implementationMethodId != null) {
            return null;
        }
        return switch (s.implementationBasis) {
            case "NO_SOURCE_IMPLEMENTATION" -> "source 구현 없음";
            case "MULTIPLE_SOURCE_IMPLEMENTATIONS" -> "구현 " + s.implementationCandidates.size() + "개 — 미연결";
            case "HIERARCHY_UNRESOLVED" -> "구현 계층 판정 불가 — 미연결";
            default -> "구현 메서드 불일치 — 미연결";
        };
    }

    /** Request Parameter 1건 표시: status / ?(expr) / (전체) / (body) */
    public static String parameterName(RequestParameterInfo p) {
        if ("UNKNOWN".equals(p.nameResolution)) {
            return "?(" + truncate(p.nameExpression, 30) + ")";
        }
        if ("@ModelAttribute".equals(p.extraction)) {
            return "(바인딩 " + p.variable + ")";
        }
        if (p.name == null) {
            return "REQUEST_BODY".equals(p.nameResolution) ? "(body)" : "(전체)";
        }
        return p.name;
    }

    /** 호출 위치에서 읽은 요청 파라미터: REQ status, page (없으면 null) */
    public static String requestTag(AnalysisResult r, CallSiteInfo s) {
        List<RequestParameterInfo> ps = r.parametersAt(s.id);
        if (ps.isEmpty()) {
            return null;
        }
        return "REQ " + String.join(", ", ps.stream().map(Labels::parameterName).toList());
    }

    /** 메서드 선언부(Spring 어노테이션)에서 받은 요청 파라미터: REQ @PathVariable status, ... (없으면 null) */
    public static String methodRequestTag(AnalysisResult r, MethodInfo m) {
        List<RequestParameterInfo> ps = r.parameters.stream()
                .filter(p -> p.callSiteId == null && m.id.equals(p.sourceMethodId)).toList();
        if (ps.isEmpty()) {
            return null;
        }
        return "REQ " + String.join(", ", ps.stream().map(p -> p.extraction + " " + parameterName(p)).toList());
    }

    /** 바인딩 출처 표시: request.appId + literal:x (없으면 -) */
    public static String bindingSource(Binding b) {
        if (b.sources.isEmpty()) {
            return "-";
        }
        return String.join(" + ", b.sources.stream().map(v -> v.display()
                + (v.via().isEmpty() ? "" : " (via " + String.join("→", v.via()) + ")")).toList());
    }

    /** 바인딩 변환 표시: Integer.parseInt → .trim() (없으면 -) */
    public static String bindingTransform(Binding b) {
        return b.transforms.isEmpty() ? "-" : String.join(" → ", b.transforms);
    }

    /** 바인딩 조건 표시: always / cond1 && cond2 */
    public static String bindingCondition(Binding b) {
        return b.conditions.isEmpty() ? "always" : String.join(" && ", b.conditions);
    }

    /** 흐름도/트리용 객체 바인딩 요약: [OrderVO (order), 7 fields assigned, 2 conditional assignments, 1 type conversion] */
    public static List<String> bindingSummary(AnalysisResult r, ObjectInfo o, List<Binding> bs) {
        List<String> l = new ArrayList<>();
        l.add(o.className + " (" + o.variable + ")");
        long fields = bs.stream().map(b -> b.field).distinct().count();
        long conditional = bs.stream().filter(Binding::conditional).count();
        long conversions = bs.stream().filter(b -> !b.transforms.isEmpty()).count();
        l.add(fields + (fields == 1 ? " field assigned" : " fields assigned"));
        if (conditional > 0) {
            l.add(conditional + (conditional == 1 ? " conditional assignment" : " conditional assignments"));
        }
        if (conversions > 0) {
            l.add(conversions + (conversions == 1 ? " type conversion" : " type conversions"));
        }
        long unresolved = bs.stream().filter(b -> b.resolution != ResolutionStatus.RESOLVED).count();
        if (unresolved > 0) {
            l.add(unresolved + " unresolved");
        }
        return l;
    }

    /** [Role, visibility, Dn, ...] */
    public static String callTags(AnalysisResult r, CallSiteInfo s) {
        List<String> tags = new ArrayList<>();
        if (s.targetMethodId != null) {
            MethodInfo t = r.method(s.targetMethodId);
            MethodInfo caller = r.method(s.callerMethodId);
            ClassInfo tc = r.classOf(t);
            if (!t.classId.equals(caller.classId)) {
                tags.add(tc.role.label());
            }
            tags.add(t.visibility.text());
        }
        tags.add("D" + s.depth);
        if (s.resolution != ResolutionStatus.RESOLVED) {
            tags.add(s.resolution.name());
        }
        return "[" + String.join(", ", tags) + "]";
    }

    public static String blockHeader(ControlBlock b) {
        return switch (b.type) {
            case IF -> "if (" + truncate(b.condition, 80) + ")";
            case ELSE_IF -> "else if (" + truncate(b.condition, 80) + ")";
            case ELSE -> "else";
            case SWITCH -> "switch (" + truncate(b.condition, 60) + ")";
            case CASE -> b.caseLabels.isEmpty() ? "default" : "case " + truncate(String.join(", ", b.caseLabels), 60)
                    + (b.isDefault ? ", default" : "");
            case FOR, FOREACH -> "for (" + truncate(b.condition, 80) + ")";
            case WHILE -> "while (" + truncate(b.condition, 80) + ")";
            case DO_WHILE -> "do-while (" + truncate(b.condition, 80) + ")";
            case TRY -> b.resources.isEmpty() ? "try"
                    : "try (resources: " + String.join(", ", b.resources.stream().map(x -> x.variable()).toList()) + ")";
            case CATCH -> "catch " + String.join(" | ", b.exceptionTypes);
            case FINALLY -> "finally";
            case RETURN -> b.expression == null ? "return" : "return " + truncate(b.expression, 70);
            case THROW -> "throw " + truncate(b.expression, 70);
        };
    }

    /** 블록에 붙는 부가 표시 */
    public static String blockNotes(ControlBlock b) {
        List<String> n = new ArrayList<>();
        if (b.type == BlockType.CATCH && b.rethrows) {
            n.add("rethrows");
        }
        if (b.type == BlockType.CATCH && b.throwsNewException) {
            n.add("throws new");
        }
        if (b.type == BlockType.FINALLY && (b.containsReturn || b.containsThrow)) {
            n.add("finally 내 " + (b.containsReturn ? "return" : "throw") + " → 원래 종료 흐름 대체 가능");
        }
        if (b.type == BlockType.THROW) {
            if (b.caughtBy != null) {
                n.add("caught by " + b.caughtBy);
            } else if (Boolean.TRUE.equals(b.propagatesToCaller)) {
                n.add("호출자로 전파");
            } else {
                n.add("예외 흐름 unresolved");
            }
        }
        if ((b.type == BlockType.RETURN || b.type == BlockType.THROW) && !b.executesFinally.isEmpty()) {
            n.add("finally 경유: " + String.join(", ", b.executesFinally));
        }
        if (b.deferred) {
            n.add("deferred");
        }
        return n.isEmpty() ? "" : "  ⟨" + String.join("; ", n) + "⟩";
    }

    /** 메서드(또는 블록) 직계 요소: 호출 + BODY 자식 블록, seq 순 */
    public static List<Object> elements(AnalysisResult r, String methodId, String blockId, boolean includeBranches) {
        List<Object> out = new ArrayList<>();
        for (CallSiteInfo s : r.callSites) {
            if (s.callerMethodId.equals(methodId) && java.util.Objects.equals(s.controlBlockId, blockId)) {
                out.add(s);
            }
        }
        for (ControlBlock b : r.blocks) {
            if (b.methodId.equals(methodId) && java.util.Objects.equals(b.parentBlockId, blockId)
                    && (includeBranches || !"BRANCH".equals(b.relation))) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparingInt(Labels::seq));
        return out;
    }

    public static int seq(Object o) {
        return o instanceof CallSiteInfo s ? s.seq : ((ControlBlock) o).seq;
    }
}
