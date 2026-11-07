package com.mm.action;

import com.mm.service.MemberService;
import com.mm.util.DupUtil;
import com.mm.vo.MemberVO;

import javax.servlet.http.HttpServletRequest;

/** actions 모듈의 Action → lib 모듈 Service/Mapper 로 이어지는 멀티 모듈 fixture */
public class MemberAction {
    private MemberService memberService;

    public String execute(HttpServletRequest request) {
        MemberVO vo = new MemberVO();
        vo.setMemberId(request.getParameter("memberId"));
        MemberVO found = memberService.findMember(vo);
        DupUtil.check(found);
        return "success";
    }
}
