package com.shop.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import com.shop.vo.DeliveryVO;
import com.shop.vo.OrderVO;

@Mapper
public interface OrderMapper {
    List<Map<String, Object>> selectOrders(Map<String, Object> cond, int offset, int limit);

    int countOrder(String orderNo);

    long selectNextSeq();

    int insertOrder(OrderVO order);

    int updateOrder(OrderVO order);

    int insertDelivery(DeliveryVO delivery);

    int updatePayment(String orderNo, String approvalNo);
}
