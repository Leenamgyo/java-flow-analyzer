package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;

import analyzer.model.SourceLocation;
import analyzer.model.VariableChange;

import java.util.ArrayList;
import java.util.List;

/** 진입 메서드를 루트로 하는 텍스트 호출 트리. */
public final class CallTreeWriter {
    private final boolean includeBlocks;
    private List<String> excludes = List.of();
    private int excluded;
    private AnalysisResult r;
    private StringBuilder sb;

    public CallTreeWriter(boolean includeBlocks) {
        this.includeBlocks = includeBlocks;
    }

    /** output.call-tree-exclude — 맞는 호출은 줄도, 그 아래 전개도 쓰지 않는다 */
    public CallTreeWriter exclude(List<String> patterns) {
        this.excludes = patterns == null ? List.of() : List.copyOf(patterns);
        return this;
    }

    public String render(AnalysisResult result) {
        this.r = result;
        this.sb = new StringBuilder();
        this.excluded = 0;
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        sb.append(target.name).append(" [").append(target.role.label()).append("]\n");
        sb.append("└── ").append(entry.signature).append(" [").append(entry.visibility.text()).append(", D0]");
        String req = Labels.methodRequestTag(r, entry);
        if (req != null) {
            sb.append("  ⇐ ").append(req);
        }
        sb.append('\n');
        renderMethod(entry, "    ");
        if (!excludes.isEmpty()) {
            sb.append("\n※ call-tree-exclude (사용자 설정으로 뺀 호출 ").append(excluded).append("개): ")
                    .append(String.join(", ", excludes)).append('\n');
        }
        return sb.toString();
    }

    private void renderMethod(MethodInfo m, String prefix) {
        List<Object> items = includeBlocks
                ? withChanges(m, null, FlowView.elements(r, m.id, null, true))
                : r.callSitesOf(m.id).stream().filter(s -> !FlowView.hidden(r, s)).map(s -> (Object) s).toList();
        renderItems(m, items, prefix);
    }

    /** 변수 값 변경·호출 없는 선언(≔)을 평가 순서 위치에 끼운다: 식 안의 호출이 끝난 뒤 */
    private List<Object> withChanges(MethodInfo m, String container, List<Object> items) {
        // 값 변경 + 호출 없는 선언(boolean data = false) — 선언 코드도 흐름에 보이게
        List<VariableChange> cs = new ArrayList<>(r.changesIn(m.id, container));
        cs.addAll(r.declarationsIn(m.id, container));
        cs.sort(java.util.Comparator.comparingInt((VariableChange c) -> c.location.beginLine())
                .thenComparingInt(c -> c.location.beginColumn()));
        if (cs.isEmpty()) {
            return items;
        }
        List<Object> out = new ArrayList<>(items);
        for (VariableChange c : cs) {
            int at = out.size();
            for (int i = 0; i < out.size(); i++) {
                SourceLocation l = location(out.get(i));
                if (l != null && (c.location.endLine() < l.beginLine()
                        || c.location.endLine() == l.beginLine() && c.location.endColumn() < l.beginColumn())) {
                    at = i;
                    break;
                }
            }
            out.add(at, c);
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
        if (o instanceof VariableChange c) {
            return c.location;
        }
        return ((FlowView.Summary) o).at();
    }

    private void renderItems(MethodInfo m, List<Object> all, String prefix) {
        List<Object> items = all.stream().filter(o -> {
            boolean out = o instanceof CallSiteInfo s && Excludes.call(r, excludes, s);
            if (out) {
                excluded++;
            }
            return !out;
        }).toList();
        for (int i = 0; i < items.size(); i++) {
            boolean last = i == items.size() - 1;
            String branch = last ? "└── " : "├── ";
            String childPrefix = prefix + (last ? "    " : "│   ");
            Object o = items.get(i);
            if (o instanceof VariableChange c) {
                sb.append(prefix).append(branch).append("≔ ").append(Labels.changeLine(r, c))
                        .append("  (").append(c.id).append(")\n");
            } else if (o instanceof FlowView.Summary sm) {
                sb.append(prefix).append(branch).append("✎ ")
                        .append(String.join(" · ", Labels.bindingSummary(r, sm.object(), sm.bindings())))
                        .append("  (").append(sm.object().id).append(")\n");
            } else if (o instanceof CallSiteInfo s) {
                sb.append(prefix).append(branch).append(callLine(s)).append('\n');
                if (s.expansion == Expansion.EXPANDED) {
                    renderMethod(r.method(s.expansionTargetId()), childPrefix);
                }
            } else {
                ControlBlock b = (ControlBlock) o;
                sb.append(prefix).append(branch).append(Labels.blockHeader(b)).append("  (").append(b.id).append(")")
                        .append(Labels.blockNotes(b)).append('\n');
                renderItems(m, withChanges(m, b.id, FlowView.elements(r, m.id, b.id, true)), childPrefix);
            }
        }
    }

    private String callLine(CallSiteInfo s) {
        StringBuilder l = new StringBuilder();
        l.append(Labels.callTarget(r, s)).append(' ').append(Labels.callTags(r, s));
        if (!"BODY".equals(s.position)) {
            l.append(" <").append(s.position.toLowerCase()).append('>');
        }
        if (!includeBlocks && s.conditional) {
            l.append(" ?conditional");
        }
        if (s.deferred) {
            l.append(" ~deferred");
        }
        l.append("  (").append(s.id).append(')');
        String req = Labels.requestTag(r, s);
        if (req != null) {
            l.append("  ⇐ ").append(req);
        }
        String impl = Labels.implementationTarget(r, s);
        if (impl != null) {
            l.append("  ⇒ 구현 후보 ").append(impl);
        }
        String skipped = Labels.implementationSkipped(s);
        switch (s.expansion) {
            case CYCLE -> l.append("  ↺ cycle");
            case SHARED -> l.append("  → ").append(s.expandedAt).append(" 에서 전개됨");
            case DEPTH_LIMIT -> l.append("  ⋯ depth-limit");
            case NO_BODY -> l.append("  ⋯ abstract/interface (")
                    .append(skipped != null ? skipped : "구현체 미확정").append(')');
            case NOT_APPLICABLE -> l.append("  ✗ unresolved");
            default -> {
            }
        }
        List<String> sql = Labels.sqlLines(s.sql);
        if (!sql.isEmpty()) {
            l.append("  ⛁ ").append(String.join(" · ", sql));
        }
        return l.toString();
    }
}
