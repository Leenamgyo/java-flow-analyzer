package com.example.action.vas;

import javax.servlet.http.HttpServletRequest;
import com.example.util.StringUtil;

/** 상속받은 요청 읽기 메서드 + 이름 인자 없는 읽기 + 유틸 읽기 */
public class GetVasAppList extends VasBaseAction {

    public String execute(HttpServletRequest request) {
        String deviceId = getVasDeviceIdParameter(request, "deviceId");
        String systemId = getSystemId(request);
        String abi = getDeviceAbiType(request);
        String query = StringUtil.getParam(request, "query");
        return deviceId + systemId + abi + query;
    }
}
