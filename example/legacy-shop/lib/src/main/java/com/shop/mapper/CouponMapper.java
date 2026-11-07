package com.shop.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponMapper {
    Map<String, Object> selectCoupon(String memberId, String couponCode);
}
