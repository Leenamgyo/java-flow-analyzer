package com.mm.service;

import com.mm.mapper.MemberMapper;
import com.mm.vo.MemberVO;

public class MemberServiceImpl implements MemberService {
    private MemberMapper memberMapper;

    @Override
    public MemberVO findMember(MemberVO vo) {
        return memberMapper.selectMember(vo);
    }
}
