package com.shop.web;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.shop.common.OrderException;
import com.shop.service.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderApiController {
    private OrderService orderService;

    @GetMapping("/{status}")
    public List<Map<String, Object>> list(@PathVariable String status,
                                          @RequestParam(value = "page", defaultValue = "1") int page,
                                          @RequestParam(name = "size", required = false) Integer size,
                                          @RequestHeader("X-User-Id") String userId,
                                          @RequestBody(required = false) Map<String, Object> filter) {
        try {
            return orderService.findOrders(filter, page, size == null ? 20 : size);
        } catch (OrderException e) {
            throw new IllegalStateException(e.getCode(), e);
        }
    }
}
