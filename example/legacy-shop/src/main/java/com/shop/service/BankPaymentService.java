package com.shop.service;

import org.springframework.stereotype.Service;
import com.shop.common.PaymentException;
import com.shop.vo.PaymentDTO;

@Service("bankPaymentService")
public class BankPaymentService implements PaymentService {
    @Override
    public void approve(PaymentDTO payment) throws PaymentException {
        payment.setApprovalNo("B" + System.currentTimeMillis());
    }

    @Override
    public void cancel(PaymentDTO payment) throws PaymentException {
    }
}
