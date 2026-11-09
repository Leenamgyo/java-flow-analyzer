package com.example.action.vas;

import com.example.util.StringUtil;
import com.example.util.VasUtil;

import javax.servlet.http.HttpServletRequest;

/**
 * 요청 읽기 경계 사례:
 * 기본값이 함수 호출 — StringUtil.getParameter(request, "scVersion", VasUtil.getExceptionScVersion(appId)),
 * 단순 래퍼(StringUtil.getParameter: 읽기 + 기본값) vs 로직 있는 읽기 함수(getClientVersion: 가공·다른 호출).
 */
public class ScVersionAction {

    public String execute(HttpServletRequest request) {
        String appId = request.getParameter("appId");
        String scVersion = StringUtil.getParameter(request, "scVersion", VasUtil.getExceptionScVersion(appId));
        String mode = StringUtil.getParameter(request, "mode", "LIST");
        String clientVersion = getClientVersion(request, "clientVersion");
        String page = StringUtil.getParameter(request, "page"); // 인자 2개 오버로드 — 같은 규칙(default-arg-index: 2)
        String lang = StringUtil.getParameter(request);          // 이름 인자 없음 — 규칙 미적용, 오류 없음
        return scVersion + mode + clientVersion + page + lang;
    }

    /** 요청 읽기 + 로직 (접두사 제거·정규화) */
    protected String getClientVersion(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        if (v != null && v.startsWith("v")) {
            v = v.substring(1);
        }
        return VasUtil.normalize(v);
    }
}
