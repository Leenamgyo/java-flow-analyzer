package com.example.biz;

import com.example.dao.NoticeDAO;
import com.example.model.NoticeVO;

/** Biz 안 VO 추적 (BizVoTest): 받은 VO 에 set · 그 VO 를 DAO 로 · Biz 안에서 만든 VO 를 DAO 로 */
public class NoticeWriteBiz {
    private NoticeDAO noticeDAO;

    public void write(NoticeVO vo, String body) {
        vo.setBody(body);
        noticeDAO.insert(vo);
        NoticeVO history = new NoticeVO();
        history.setTitle(vo.getTitle());
        history.setBody("written");
        noticeDAO.insertHistory(history);
    }
}
