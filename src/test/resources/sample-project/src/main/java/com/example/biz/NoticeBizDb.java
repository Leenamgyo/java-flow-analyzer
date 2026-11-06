package com.example.biz;

import java.util.List;

public class NoticeBizDb implements NoticeBiz {
    @Override
    public List<String> list(String type) {
        return List.of(type);
    }
}
