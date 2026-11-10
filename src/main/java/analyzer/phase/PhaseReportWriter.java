package analyzer.phase;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.CallSiteInfo;
import analyzer.model.ClassRole;
import analyzer.model.MethodInfo;
import analyzer.model.ObjectInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.phase.PhaseModel.CheckItem;
import analyzer.phase.PhaseModel.Region;
import analyzer.phase.PhaseModel.Step;
import analyzer.serializer.Labels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** phase-summary.md: 단계(바인딩 › 체크 › 로직 › 후처리)별 요약. PhaseModel(= 공통 모델에서 계산)만 사용한다. */
public final class PhaseReportWriter {

    public String render(AnalysisResult r, PhaseModel pm) {
        MethodInfo entry = r.method(r.entryMethodId);
        StringBuilder sb = new StringBuilder();
        sb.append("# 단계 보기: ").append(r.classOf(entry).name).append('.').append(entry.name).append("()\n\n");
        sb.append("> 진입 메서드의 최상위 요소(try 본문은 펼침)를 **바인딩 › 체크 › 로직 › 후처리** 로 판정한 결과입니다. ")
                .append("판정은 분석 모델(요청 읽기·바인딩·클래스 역할·분기 종료 형태)만 사용하며, 요소별 근거는 맨 아래 표에 있습니다. ")
                .append("필드 매핑 상세는 `mapping.md`, 문장 단위 전체 흐름은 `flowchart.mmd` / `call-tree.txt` 를 보세요.\n\n");
        sb.append("판정 규칙\n\n");
        pm.rules.forEach(x -> sb.append("- ").append(x).append('\n'));
        sb.append('\n');

        // ---- 순서
        sb.append("## 실제 실행 순서\n\n");
        sb.append(segments(pm)).append("\n\n");
        List<Step> inLogic = pm.region(Region.LOGIC).stream().filter(s -> s.phase != Phase.LOGIC).toList();
        if (!inLogic.isEmpty()) {
            sb.append("로직 사이에 끼어 있는 요소 (흐름도 ③ 안의 요약 노드):\n\n");
            inLogic.forEach(s -> sb.append("- L").append(s.location.beginLine()).append(" `").append(PhaseText.stepLabel(s))
                    .append("` — ").append(s.phase.label()).append(": ").append(s.reason).append('\n'));
            sb.append('\n');
        }

        // ---- 바인딩
        sb.append("## ").append(Phase.BINDING.title()).append("\n\n");
        sb.append("### 요청 파라미터\n\n");
        List<String[]> rows = new ArrayList<>();
        for (Step st : pm.steps) {
            for (RequestParameterInfo p : st.parameters) {
                rows.add(new String[]{"`" + Labels.parameterName(p) + "`", p.extraction, p.defaultValue == null ? "-" : "`" + p.defaultValue + "`",
                        r.method(p.sourceMethodId).name + " · L" + p.location.beginLine(), regionLabel(st)});
            }
        }
        table(sb, new String[]{"이름", "읽는 방식", "기본값", "위치", "단계"}, rows);

        List<Step> fetch = pm.steps.stream().filter(s -> s.fetchVariable != null).toList();
        if (!fetch.isEmpty()) {
            sb.append("### 값 조회 (바인딩 출처로 쓰이는 호출)\n\n");
            fetch.forEach(s -> {
                sb.append("- L").append(s.location.beginLine()).append(" `").append(s.fetchVariable).append(" = ")
                        .append(PhaseText.stepLabel(s)).append("`\n");
            });
            sb.append('\n');
        }

        sb.append("### 객체\n\n");
        rows = new ArrayList<>();
        for (Region rg : Region.values()) {
            List<Step> steps = pm.region(rg).stream().filter(s -> s.phase != Phase.LOGIC || rg != Region.LOGIC).toList();
            for (Map.Entry<ObjectInfo, List<Binding>> e : PhaseText.objects(r, steps).entrySet()) {
                ObjectInfo o = e.getKey();
                List<Binding> bs = e.getValue();
                rows.add(new String[]{o.id, "`" + o.className + "`", "`" + o.variable + "`",
                        String.valueOf(bs.stream().map(b -> b.field).distinct().count()),
                        String.valueOf(bs.stream().filter(Binding::conditional).count()),
                        o.methodId == null ? "(필드)" : r.method(o.methodId).name, regionTitle(rg)});
            }
        }
        table(sb, new String[]{"Object", "Class", "변수", "할당 필드", "조건부 할당", "소유 메서드", "단계"}, rows);
        List<Step> preAdjust = pm.region(Region.PRE).stream().filter(s -> !s.changes.isEmpty()).toList();
        if (!preAdjust.isEmpty()) {
            sb.append("### 값 보정·변경 (첫 로직 전)\n\n");
            preAdjust.forEach(s -> s.changes.forEach(c -> sb.append("- L").append(c.location.beginLine()).append(" `")
                    .append(Labels.changeLine(r, c)).append("` — 조건 ")
                    .append(c.conditions.isEmpty() ? "always" : "`" + String.join(" && ", c.conditions) + "`").append('\n')));
            sb.append('\n');
        }
        List<String> ms = PhaseText.bindingMethods(r, pm.steps);
        if (!ms.isEmpty()) {
            sb.append("바인딩 메서드: ").append(String.join(", ", ms.stream().map(x -> "`" + x + "`").toList())).append("\n\n");
        }

        // ---- 체크
        sb.append("## ").append(Phase.CHECK.title()).append("\n\n");
        checkTable(sb, pm.preChecks());
        List<CheckItem> later = new ArrayList<>();
        pm.steps.stream().filter(s -> s.region != Region.PRE).forEach(s -> later.addAll(s.checks));
        if (!later.isEmpty()) {
            sb.append("### 로직·후처리 중 체크\n\n");
            checkTable(sb, later);
        }

        // ---- 로직
        sb.append("## ").append(Phase.LOGIC.title()).append("\n\n");
        if (pm.firstLogic < 0) {
            sb.append("로직 요소 없음.\n\n");
        }
        for (Step st : pm.region(Region.LOGIC)) {
            sb.append("- L").append(st.location.beginLine()).append(" `").append(PhaseText.stepLabel(st)).append('`');
            if (st.phase != Phase.LOGIC) {
                sb.append(" — (").append(st.phase.label()).append(')');
            }
            sb.append('\n');
            if (st.phase == Phase.LOGIC) {
                logicCalls(sb, r, pm, st);
            }
            st.changes.forEach(c -> sb.append("  - ≔ ").append(Labels.changeLine(r, c)).append('\n'));
        }
        sb.append('\n');

        // ---- 후처리
        sb.append("## ").append(Phase.POST.title()).append("\n\n");
        List<Step> post = pm.region(Region.POST);
        if (post.isEmpty()) {
            sb.append("없음.\n\n");
        } else {
            post.forEach(s -> sb.append("- L").append(s.location.beginLine()).append(" `").append(PhaseText.stepLabel(s))
                    .append("` — ").append(s.phase == Phase.END ? "종료" : s.reason).append('\n'));
            sb.append('\n');
        }

        // ---- 근거
        sb.append("## 단계 판정 근거\n\n");
        rows = new ArrayList<>();
        for (Step st : pm.steps) {
            rows.add(new String[]{String.valueOf(st.index), "`" + PhaseText.stepLabel(st) + "`", "L" + st.location.beginLine(),
                    st.phase.label(), st.region.name(), st.reason});
        }
        table(sb, new String[]{"#", "요소", "위치", "단계", "영역", "근거"}, rows);
        return sb.toString();
    }

    /** 바인딩(L47–50) → 체크(L52) → … */
    static String segments(PhaseModel pm) {
        List<String> out = new ArrayList<>();
        Phase cur = null;
        int from = 0;
        int to = 0;
        for (Step st : pm.steps) {
            if (st.phase != cur) {
                if (cur != null) {
                    out.add(cur.label() + "(L" + from + (to != from ? "–" + to : "") + ")");
                }
                cur = st.phase;
                from = st.location.beginLine();
            }
            to = st.location.beginLine();
        }
        if (cur != null) {
            out.add(cur.label() + "(L" + from + (to != from ? "–" + to : "") + ")");
        }
        return String.join(" → ", out);
    }

    /** 로직 역할 호출 트리 (전개 순서, 호출 Depth 로 들여쓰기) */
    private static void logicCalls(StringBuilder sb, AnalysisResult r, PhaseModel pm, Step st) {
        int base = st.calls.isEmpty() ? 0 : st.calls.get(0).depth;
        for (CallSiteInfo c : st.calls) {
            if (c.targetMethodId == null) {
                continue;
            }
            ClassRole role = r.classOf(r.method(c.targetMethodId)).role;
            ClassRole impl = c.implementationMethodId == null ? null : r.classOf(r.method(c.implementationMethodId)).role;
            if (!pm.logicRoles.contains(role) && (impl == null || !pm.logicRoles.contains(impl))) {
                continue;
            }
            sb.append("  ".repeat(Math.max(1, c.depth - base + 1))).append("- ").append(Labels.callTarget(r, c))
                    .append(" [").append(role.label()).append(", D").append(c.depth).append(']');
            String implT = Labels.implementationTarget(r, c);
            if (implT != null) {
                sb.append(" ⇒ 구현 후보 ").append(implT);
            }
            if (c.conditional) {
                sb.append(" · 조건부");
            }
            sb.append('\n');
        }
    }

    private static void checkTable(StringBuilder sb, List<CheckItem> checks) {
        List<String[]> rows = new ArrayList<>();
        int i = 0;
        for (CheckItem c : checks) {
            rows.add(new String[]{String.valueOf(++i), "`" + c.condition() + "`", "`" + c.outcome() + "`", c.context(),
                    c.location() == null ? "-" : c.location().shortText()});
        }
        table(sb, new String[]{"#", "조건", "결과", "출처", "위치"}, rows);
    }

    private static String regionLabel(Step st) {
        return st.region == Region.LOGIC && st.phase == Phase.LOGIC ? Phase.LOGIC.title()
                : st.region == Region.PRE ? (st.phase == Phase.CHECK ? Phase.CHECK.title() : Phase.BINDING.title())
                : st.region == Region.POST ? Phase.POST.title() : Phase.LOGIC.title() + " 중 " + st.phase.label();
    }

    private static String regionTitle(Region rg) {
        return switch (rg) {
            case PRE -> Phase.BINDING.title();
            case LOGIC -> Phase.LOGIC.title() + " 중";
            case POST -> Phase.POST.title();
        };
    }

    private static void table(StringBuilder sb, String[] header, List<String[]> rows) {
        if (rows.isEmpty()) {
            sb.append("없음.\n\n");
            return;
        }
        sb.append("| ").append(String.join(" | ", header)).append(" |\n|");
        sb.append("---|".repeat(header.length)).append('\n');
        for (String[] row : rows) {
            List<String> cells = new ArrayList<>();
            for (String c : row) {
                cells.add(c == null ? "-" : c.replace("|", "\\|").replace("\n", " "));
            }
            sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        sb.append('\n');
    }
}
