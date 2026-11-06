package com.example.biz;

import java.util.List;

/** 구현 클래스가 2개인 인터페이스 — 구현 후보를 연결하지 않아야 한다 */
public interface NoticeBiz {
    List<String> list(String type);
}
