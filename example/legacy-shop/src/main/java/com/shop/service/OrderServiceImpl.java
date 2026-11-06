package com.shop.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.shop.biz.StockBiz;
import com.shop.common.Constants;
import com.shop.common.OrderException;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.util.DateUtils;
import com.shop.vo.DeliveryVO;
import com.shop.vo.OrderItemVO;
import com.shop.vo.OrderVO;
import com.shop.vo.PaymentDTO;

@Service
public class OrderServiceImpl implements OrderService {
    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private StockBiz stockBiz;

    @Override
    public List<Map<String, Object>> findOrders(Map<String, Object> cond, int page, int size) throws OrderException {
        return orderMapper.selectOrders(cond, (page - 1) * size, size);
    }

    @Override
    public List<Map<String, Object>> findCanceledOrders(Map<String, Object> cond, int page, int size) throws OrderException {
        cond.put("canceled", true);
        return orderMapper.selectOrders(cond, (page - 1) * size, size);
    }

    @Override
    public String registerOrder(OrderVO order, List<OrderItemVO> items, DeliveryVO delivery, PaymentDTO payment)
            throws OrderException {
        String orderNo = order.getOrderNo();
        if (orderNo == null) {
            orderNo = nextOrderNo();
        } else if (orderMapper.countOrder(orderNo) == 0) {
            throw new OrderException("E404", "order not found: " + orderNo);
        }
        order.setOrderNo(orderNo);
        order.setStatus(Constants.STATUS_REGISTERED);
        order.setRegDate(DateUtils.now());

        for (OrderItemVO item : items) {
            item.setOrderNo(orderNo);
            item.setPrice(orderItemMapper.selectPrice(item.getItemCode()));
            stockBiz.decrease(item.getItemCode(), item.getQuantity());
        }
        delivery.setOrderNo(orderNo);

        if ("MODIFY".equals(order.getOrderType())) {
            orderMapper.updateOrder(order);
            orderItemMapper.deleteItems(orderNo);
        } else {
            orderMapper.insertOrder(order);
        }
        orderItemMapper.insertItems(items);
        orderMapper.insertDelivery(delivery);
        if (payment.getApprovalNo() != null) {
            orderMapper.updatePayment(orderNo, payment.getApprovalNo());
        }
        return orderNo;
    }

    private String nextOrderNo() {
        return "O" + orderMapper.selectNextSeq();
    }
}
