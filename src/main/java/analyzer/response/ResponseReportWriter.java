package analyzer.response;

import analyzer.model.AnalysisResult;

/** response.md — Response(JSP) 분석 모델만 그린다 (JSP 를 다시 읽지 않음). */
public final class ResponseReportWriter {

    public String render(AnalysisResult r, ResponseModel m) {
        StringBuilder sb = new StringBuilder("# Response (JSP · XML 응답)\n\n");
        sb.append("> JSP 는 읽기 전용으로 읽었습니다. 웹 루트: ").append(m.webRoots.isEmpty() ? "(못 찾음 — response.web-roots 지정)"
                : String.join(", ", m.webRoots)).append("\n\n");
        for (ResponseModel.Jsp j : m.jsps) {
            sb.append("## ").append(j.requested).append(" — ").append(j.status).append("\n\n");
            if (j.file != null) {
                sb.append("파일: `").append(j.file).append("`\n\n");
            }
            j.candidates.forEach(c -> sb.append("- 후보: `").append(c).append("`\n"));
            j.includes.forEach(c -> sb.append("- include: `").append(c).append("`\n"));
            if (!j.tree.isEmpty()) {
                sb.append("\n### XML 구조\n\n```text\n");
                j.tree.forEach(l -> sb.append(l).append('\n'));
                sb.append("```\n\n### 값 출처\n\n| XML 경로 | 값 | 반복·조건 | 출처 | 위치 |\n|---|---|---|---|---|\n");
                for (ResponseModel.Value v : j.values) {
                    sb.append("| `").append(v.path()).append("` | `").append(cell(v.value())).append("` | ")
                            .append(v.context().isEmpty() ? "always" : cell(String.join(" › ", v.context()))).append(" | ")
                            .append(v.sources().isEmpty() ? "고정 값" : cell(String.join("<br>", v.sources()))).append(" | ")
                            .append(v.at()).append(" |\n");
                }
            }
            j.notes.forEach(n -> sb.append("\n- ⚠ ").append(n));
            sb.append("\n\n");
        }
        sb.append("## Action → JSP attribute\n\n| attribute | 넣는 곳 | 값 | JSP 사용 |\n|---|---|---|---|\n");
        for (ResponseModel.Attribute a : m.attributes) {
            sb.append("| `").append(a.name()).append("` | ").append(a.callSiteId()).append(' ').append(a.at()).append(" | `")
                    .append(cell(a.value())).append("` | ").append(a.usedIn().isEmpty() ? "JSP 에서 안 씀" : String.join(", ", a.usedIn()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}
