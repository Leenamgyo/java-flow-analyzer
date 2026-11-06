package com.shop.vo;

import lombok.Builder;
import lombok.Getter;

/** 주문 결과 화면 DTO — Lombok @Builder (builder 메서드는 소스에 없음) */
@Getter
@Builder
public class OrderResultDTO {
    private String orderNo;
    private long totalAmount;
    private String payType;
    private String message;
}
