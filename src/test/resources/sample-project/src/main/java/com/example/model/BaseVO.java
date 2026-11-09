package com.example.model;

/** 공통 상위 VO (상속 필드 fixture) */
public class BaseVO {
    private String regId;
    private String regDate;

    public void setRegId(String regId) {
        this.regId = regId;
    }

    public void setRegDate(String regDate) {
        this.regDate = regDate;
    }
}
