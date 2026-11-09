package com.example.action.vas;

import com.example.util.CountryUtil;
import com.example.util.StringUtil;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 요청 값을 Action 안 ParamMgr(중첩 클래스)에 모아 두는 패턴:
 * pm.setup(request) 본문에서 요청을 읽어 ParamMgr 필드에 넣고, 이후 로직은 pm.필드를 쓴다.
 * CountryUtil = 로직이 많은 Util (국가·언어 결정).
 */
public class AppDetailAction {

    public String execute(HttpServletRequest request) {
        AppDetailAction.ParamMgr pm = new AppDetailAction.ParamMgr();
        pm.setup(request);
        String country = CountryUtil.resolveCountry(pm.mcc, pm.lang);
        List<String> apps = new ArrayList<>();
        apps.add(pm.appId);
        request.setAttribute("country", country);
        request.setAttribute("appList", apps);
        return "appDetail"; // → /WEB-INF/jsp/vas/appDetail.jsp (XML 응답)
    }

    /** 요청 셋업: 요청 값 → 필드 */
    static class ParamMgr {
        String appId;
        int page;
        String mcc;
        String lang = "ko";
        String unused;

        void setup(HttpServletRequest request) {
            appId = request.getParameter("appId");
            page = Integer.parseInt(StringUtil.getParameter(request, "page", "1"));
            this.mcc = request.getHeader("x-mcc");
            String l = request.getParameter("lang");
            if (l != null) {
                lang = l.trim();
            }
        }
    }
}
