package analyzer.response;

import java.util.ArrayList;
import java.util.List;

/**
 * Response(JSP) 분석 결과 — 분석 단계에서 만들고, 보고서는 이 모델만 그린다 (JSP 를 다시 읽지 않음).
 */
public final class ResponseModel {

    /** 설정한 JSP 하나의 결과 */
    public static final class Jsp {
        /** 설정 값 (response.jsp) */
        public String requested;
        /** FOUND | NOT_FOUND | AMBIGUOUS | NO_WEB_ROOT */
        public String status;
        /** 찾은 파일 (프로젝트 기준 상대 경로) */
        public String file;
        public final List<String> candidates = new ArrayList<>();
        /** 펼친 include 파일들 (상대 경로) */
        public final List<String> includes = new ArrayList<>();
        /** XML 구조 (들여쓰기 텍스트, 줄마다 출처 꼬리표) */
        public final List<String> tree = new ArrayList<>();
        /** 값 줄: XML 경로별 값·반복/조건·출처 */
        public final List<Value> values = new ArrayList<>();
        /** 스크립틀릿·알 수 없는 태그 등 확인할 것 */
        public final List<String> notes = new ArrayList<>();
    }

    /**
     * XML 값 하나. path = /response/apps/app/@id, kind = attribute | text | tag(커스텀 태그 출력),
     * value = JSP 원문(${app.appId}), context = 반복·조건, sources = 출처 설명들, line = JSP 줄 (file:line)
     */
    public record Value(String path, String kind, String value, List<String> context, List<String> sources, String at) {
    }

    /** Action 이 JSP 로 넘기는 attribute (request.setAttribute / model.addAttribute / mav.addObject) */
    public record Attribute(String name, String callSiteId, String code, String value, String at, List<String> usedIn) {
    }

    public final List<Jsp> jsps = new ArrayList<>();
    public final List<Attribute> attributes = new ArrayList<>();
    /** 찾은 웹 루트 (상대 경로) */
    public final List<String> webRoots = new ArrayList<>();
}
