package com.shop.service;

import java.util.List;
import java.util.Map;
import com.shop.common.OrderException;
import com.shop.vo.DeliveryVO;
import com.shop.vo.OrderItemVO;
import com.shop.vo.OrderVO;
import com.shop.vo.PaymentDTO;

public interface OrderService {
    List<Map<String, Object>> findOrders(Map<String, Object> cond, int page, int size) throws OrderException;

    List<Map<String, Object>> findCanceledOrders(Map<String, Object> cond, int page, int size) throws OrderException;

    String registerOrder(OrderVO order, List<OrderItemVO> items, DeliveryVO delivery, PaymentDTO payment) throws OrderException;
}
