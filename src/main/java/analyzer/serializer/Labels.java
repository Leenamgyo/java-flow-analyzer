package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.BlockType;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.MethodInfo;
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
