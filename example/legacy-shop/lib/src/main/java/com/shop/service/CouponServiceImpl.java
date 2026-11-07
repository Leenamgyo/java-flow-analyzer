package com.shop.service;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.shop.common.ValidationException;
import com.shop.mapper.CouponMapper;

@Service
public class CouponServiceImpl implements CouponService {
    private CouponMapper couponMapper;

    @Override
    public long discount(String memberId, String couponCode, long total) throws ValidationException {
        Map<String, Object> coupon = couponMapper.selectCoupon(memberId, couponCode);
        if (coupon == null) {
            throw new ValidationException("couponCode", "coupon not found");
        }
        long minAmount = (Long) coupon.get("minAmount");
        if (total < minAmount) {
            return 0L;
        }
        String type = (String) coupon.get("type");
        switch (type) {
            case "RATE":
                return total * (Long) coupon.get("rate") / 100;
            case "AMOUNT":
                return (Long) coupon.get("amount");
            default:
                return 0L;
        }
    }
}
