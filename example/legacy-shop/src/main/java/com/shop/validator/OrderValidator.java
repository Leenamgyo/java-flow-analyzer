package com.shop.validator;

import java.util.List;
import com.shop.common.ValidationException;
import com.shop.vo.DeliveryVO;
import com.shop.vo.OrderItemVO;
import com.shop.vo.OrderVO;

public final class OrderValidator {
    public static void validate(OrderVO order, List<OrderItemVO> items, DeliveryVO delivery) throws ValidationException {
        if (order.getMemberId() == null) {
            throw new ValidationException("memberId", "member required");
        }
        for (OrderItemVO item : items) {
            if (item.getQuantity() <= 0) {
                throw new ValidationException("qty", "invalid quantity: " + item.getItemCode());
            }
        }
        if (delivery.getZipCode() == null || delivery.getZipCode().length() != 5) {
            throw new ValidationException("zipCode", "invalid zip code");
        }
    }
}
