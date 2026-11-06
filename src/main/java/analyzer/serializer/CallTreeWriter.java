package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassInfo;
import analyzer.model.ControlBlock;
import analyzer.model.Expansion;
import analyzer.model.MethodInfo;

import java.util.List;

/** 진입 메서드를 루트로 하는 텍스트 호출 트리. */
public final class CallTreeWriter {
    private final boolean includeBlocks;
    private AnalysisResult r;
    private StringBuilder sb;

    public CallTreeWriter(boolean includeBlocks) {
        this.includeBlocks = includeBlocks;
    }

    public String render(AnalysisResult result) {
        this.r = result;
        this.sb = new StringBuilder();
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
        return sb.toString();
    }

    private void renderMethod(MethodInfo m, String prefix) {
        List<Object> items = includeBlocks
                ? FlowView.elements(r, m.id, null, true)
                : r.callSitesOf(m.id).stream().filter(s -> !FlowView.hidden(r, s)).map(s -> (Object) s).toList();
        renderItems(m, items, prefix);
    }

    private void renderItems(MethodInfo m, List<Object> items, String prefix) {
        for (int i = 0; i < items.size(); i++) {
            boolean last = i == items.size() - 1;
            String branch = last ? "└── " : "├── ";
            String childPrefix = prefix + (last ? "    " : "│   ");
            Object o = items.get(i);
            if (o instanceof FlowView.Summary sm) {
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
                renderItems(m, FlowView.elements(r, m.id, b.id, true), childPrefix);
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
        return l.toString();
    }
}
