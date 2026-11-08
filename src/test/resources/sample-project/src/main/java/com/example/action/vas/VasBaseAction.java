package com.example.action.vas;

import javax.servlet.http.HttpServletRequest;

/** 프로젝트 고유 요청 읽기 메서드를 가진 상위 Action (설정 request-parameter-rules 로 바인딩 처리) */
public abstract class VasBaseAction {

    protected String getVasDeviceIdParameter(HttpServletRequest request, String name) {
        return request.getHeader(name);
    }

    protected String getSystemId(HttpServletRequest request) {
        return request.getHeader("X-System-Id");
    }

    protected String getDeviceAbiType(HttpServletRequest request) {
        return request.getHeader("X-Abi");
    }
}
