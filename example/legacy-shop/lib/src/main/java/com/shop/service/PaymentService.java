package com.shop.service;

import com.shop.common.PaymentException;
import com.shop.vo.PaymentDTO;

/** 결제 — 구현이 2개(Card/Bank)라 정적으로 구현체를 확정할 수 없음 */
public interface PaymentService {
    void approve(PaymentDTO payment) throws PaymentException;

    void cancel(PaymentDTO payment) throws PaymentException;
}
