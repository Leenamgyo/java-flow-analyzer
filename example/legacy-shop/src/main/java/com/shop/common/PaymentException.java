package com.shop.common;

public class PaymentException extends OrderException {
    public PaymentException(String code, String message) {
        super(code, message);
    }
}
