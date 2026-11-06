package com.shop.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.shop.common.OrderException;
import com.shop.mapper.OrderMapper;

@Service
public class OrderServiceImpl implements OrderService {
    private OrderMapper orderMapper;

    @Override
    public List<Map<String, Object>> findOrders(Map<String, Object> cond, int page, int size) throws OrderException {
        return orderMapper.selectOrders(cond, (page - 1) * size, size);
    }

    @Override
    public List<Map<String, Object>> findCanceledOrders(Map<String, Object> cond, int page, int size) throws OrderException {
        cond.put("canceled", true);
        return orderMapper.selectOrders(cond, (page - 1) * size, size);
    }
}
