package com.example.action.vas;

import com.example.util.CountryUtil;
import com.example.util.StringUtil;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Action 안에 선언한 클래스로 요청 값을 모아 두는 패턴 (이름과 무관하게 탐지):
 * ParamMgr = 생성자 new ParamMgr(request) 에서 요청을 읽어 필드에, Paging = 메서드 setup(request) 에서.
 * CountryUtil = 로직이 많은 Util (국가·언어 결정).
 */
public class AppDetailAction {

    public String execute(HttpServletRequest request) {
        ParamMgr pm = new ParamMgr(request);
        Paging paging = new Paging();
        paging.setup(request);
        String country = CountryUtil.resolveCountry(pm.mcc, pm.lang);
        List<String> apps = new ArrayList<>();
        apps.add(pm.appId);
        request.setAttribute("country", country);
        request.setAttribute("appList", apps);
        return "appDetail"; // → /WEB-INF/jsp/vas/appDetail.jsp (XML 응답)
    }

    /** 요청 셋업: 생성자에서 요청 값 → 필드 */
    public static class ParamMgr {
        String appId;
        int page;
        String mcc;
        String lang = "ko";
        String unused;

        public ParamMgr(HttpServletRequest request) {
            appId = request.getParameter("appId");
            page = Integer.parseInt(StringUtil.getParameter(request, "page", "1"));
            this.mcc = request.getHeader("x-mcc");
            String l = request.getParameter("lang");
            if (l != null) {
                lang = l.trim();
            }
        }
    }

    /** 요청 셋업: 메서드에서 */
    public class Paging {
        int startNum;
        int count;

        void setup(HttpServletRequest request) {
            startNum = Integer.parseInt(request.getParameter("startNum"));
            count = 20;
        }
    }
}
