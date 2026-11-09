package analyzer.serializer;

import analyzer.model.AnalysisResult;
import analyzer.model.Binding;
import analyzer.model.ClassInfo;
import analyzer.model.MethodInfo;
import analyzer.model.RequestParameterInfo;
import analyzer.model.RequestSpecEntry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static analyzer.serializer.MappingReportWriter.md;

/**
 * spec.md — 분석이 끝난 결과를 명세 문서로 간결하게 정리한다 (사용자 요구: 명세는 간결하게, 장 단위로 늘려 감).
 * 1. Request Param 정의 = 일반 API 명세의 Request 표 하나: 파라미터 · 타입 · 필수 · 기본값 · 설명.
 * 분석 근거(읽는 곳·저장 필드·검증 위치)는 넣지 않는다 — analysis.md 1장. 모델에서만 만든다.
 */
public final class SpecWriter {

    public String render(AnalysisResult r) {
        return render(r, null);
    }

    /** response: Response(JSP) 분석 결과 (response.jsp 를 지정했을 때만, 없으면 2장 없음) */
    public String render(AnalysisResult r, analyzer.response.ResponseModel response) {
        StringBuilder sb = new StringBuilder();
        ClassInfo target = r.clazz(r.targetClassId);
        MethodInfo entry = r.method(r.entryMethodId);
        sb.append("# 명세: ").append(target.name).append('.').append(entry.name).append("()\n\n");
        requestParams(r, sb);
        if (response != null) {
            responseDef(response, sb);
        }
        return sb.toString();
    }

    private void requestParams(AnalysisResult r, StringBuilder sb) {
        sb.append("## 1. Request Param 정의\n\n");
        if (r.requestSpec.isEmpty()) {
            sb.append("없음.\n");
        } else {
            sb.append("| 파라미터 | 타입 | 필수 | 기본값 | 설명 |\n|---|---|---|---|---|\n");
            for (RequestSpecEntry e : r.requestSpec) {
                sb.append("| ").append(e.name).append(" | ")
                        .append(e.types.isEmpty() ? "-" : md(String.join(", ", e.types))).append(" | ")
                        // 사용자 요구: 필수 여부는 소스로 알 수 없어(StringUtil.is… 검사 등) 어노테이션에 적힌 경우 말고는 N
                        .append(e.required.isEmpty() ? "N"
                                : String.join(", ", e.required.stream().map(b -> b ? "Y" : "N").toList())).append(" | ")
                        .append(e.defaultValues.isEmpty() ? "-" : md(String.join(", ", e.defaultValues))).append(" | ")
                        .append(md(description(r, e))).append(" |\n");
            }
        }
        long unknown = r.parameters.stream().filter(p -> !p.named()).count();
        if (unknown > 0) {
            sb.append("\n> 이름을 확정하지 못한 읽기·전체 조회 ").append(unknown).append("건 — analysis.md 1장 참고\n");
        }
    }

    /** 설명: 분석으로 알 수 있는 것만 짧게 — 검증 조건 · 값 변환 (없으면 빈칸, 직접 채움) */
    private static String description(AnalysisResult r, RequestSpecEntry e) {
        Set<String> parts = new LinkedHashSet<>();
        for (String pid : e.parameterIds) {
            RequestParameterInfo p = r.parameter(pid);
            for (String v : AnalysisReportWriter.validations(r, p)) {
                parts.add("조건 " + v.replaceFirst("^(if|else if|while|switch) \\((.*)\\)(.*)$", "$2$3"));
            }
        }
        for (String id : e.bindingIds) {
            Binding b = r.binding(id);
            if (!b.transforms.isEmpty()) {
                parts.add(String.join(" → ", b.transforms));
            }
        }
        return String.join(" · ", List.copyOf(parts));
    }

    /**
     * 2. Response 정의: JSP 가 만드는 XML 의 값 항목마다 한 줄 — 항목(XML 경로) · 필수(조건 안이면 N) · 반복(forEach 안이면 Y) · 값
     * (고정 값 / request.이름 / Action attribute 이름 / 반복 원소 attr[] / 순번). 출처 사슬·위치는 response.md.
     */
    private void responseDef(analyzer.response.ResponseModel m, StringBuilder sb) {
        sb.append("\n## 2. Response 정의\n\n");
        for (analyzer.response.ResponseModel.Jsp j : m.jsps) {
            if (m.jsps.size() > 1) {
                sb.append("### ").append(j.requested).append("\n\n");
            }
            if (!"FOUND".equals(j.status)) {
                sb.append("> ").append(j.requested).append(" — ").append(j.status)
                        .append(j.candidates.isEmpty() ? "" : " (후보 " + j.candidates.size() + "개, 앞 폴더까지 지정)").append("\n\n");
                continue;
            }
            sb.append("JSP: `").append(j.file).append("`\n\n");
            sb.append("| 항목 | 필수 | 반복 | 값 |\n|---|---|---|---|\n");
            for (analyzer.response.ResponseModel.Value v : j.values) {
                boolean cond = v.context().stream().anyMatch(c -> c.startsWith("if ") || c.equals("otherwise"));
                boolean loop = v.context().stream().anyMatch(c -> c.startsWith("loop "));
                sb.append("| ").append(md(item(v.path()))).append(" | ").append(cond ? "N" : "Y").append(" | ")
                        .append(loop ? "Y" : "").append(" | ").append(md(responseValue(v))).append(" |\n");
            }
            sb.append('\n');
        }
    }

    /** /response/country/text() → response/country, /response/result/@code → response/result@code */
    static String item(String path) {
        return path.replaceFirst("^/", "").replaceFirst("/text\\(\\)$", "").replace("/@", "@");
    }

    /** 값 한 칸: 출처마다 짧게 (고정 값이면 그 값) */
    static String responseValue(analyzer.response.ResponseModel.Value v) {
        if (v.sources().isEmpty()) {
            return "고정 " + v.value();
        }
        java.util.Set<String> out = new LinkedHashSet<>();
        for (String src : v.sources()) {
            String[] seg = src.split(" ← ");
            String ref = seg[0];
            String rest = seg.length > 1 ? seg[1] : "";
            if (rest.startsWith("요청 파라미터 ")) {
                out.add(rest.substring("요청 파라미터 ".length()));
            } else if (rest.startsWith("반복 상태")) {
                out.add("순번 (" + ref + ")");
            } else if (rest.startsWith("요청 헤더 ")) {
                out.add("header " + rest.substring("요청 헤더 ".length()));
            } else if (rest.contains("[]")) {
                out.add(rest); // 반복 원소: appList[] / appList[].appId
            } else if (rest.matches("(request |session |application |page )?attribute .*")) {
                String name = rest.replaceFirst("^(\\w+ )?attribute ", "").split(" ", 2)[0];
                out.add(name + (rest.contains("넣는 곳 없음") ? " (Action 에서 넣는 곳 없음)" : ""));
            } else {
                out.add(ref);
            }
        }
        return String.join(", ", out);
    }
}
