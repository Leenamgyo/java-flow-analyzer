package com.shop.service;

import java.util.List;
import java.util.Map;
import com.shop.common.OrderException;

public interface OrderService {
    List<Map<String, Object>> findOrders(Map<String, Object> cond, int page, int size) throws OrderException;

    List<Map<String, Object>> findCanceledOrders(Map<String, Object> cond, int page, int size) throws OrderException;
}
