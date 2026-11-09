package com.example.action.vas;

import javax.servlet.http.HttpServletRequest;

/** 상위 클래스의 요청 읽기 메서드를 super. 로 부르는 경우 (request-parameter-rules 로 요청 파라미터 처리) */
public class SuperReadAction extends VasBaseAction {

    public String execute(HttpServletRequest request) {
        String systemId = super.getSystemId(request);
        String abi = this.getDeviceAbiType(request);
        String deviceId = super.getVasDeviceIdParameter(request, "deviceId");
        return systemId + abi + deviceId;
    }
}
