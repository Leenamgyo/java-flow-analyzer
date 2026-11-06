package com.shop.service;

import com.shop.common.ValidationException;

public interface CouponService {
    long discount(String memberId, String couponCode, long total) throws ValidationException;
}
