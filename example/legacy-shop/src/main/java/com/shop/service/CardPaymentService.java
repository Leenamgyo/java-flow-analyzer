package com.shop.service;

import org.springframework.stereotype.Service;
import com.shop.common.PaymentException;
import com.shop.vo.PaymentDTO;

@Service("cardPaymentService")
public class CardPaymentService implements PaymentService {
    @Override
    public void approve(PaymentDTO payment) throws PaymentException {
        if (payment.getAmount() <= 0) {
            throw new PaymentException("P010", "invalid amount");
        }
        payment.setApprovalNo("C" + System.currentTimeMillis());
    }

    @Override
    public void cancel(PaymentDTO payment) throws PaymentException {
        payment.setApprovalNo(null);
    }
}
