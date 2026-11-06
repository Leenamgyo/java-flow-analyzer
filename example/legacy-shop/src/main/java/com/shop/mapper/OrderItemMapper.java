package com.shop.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import com.shop.vo.OrderItemVO;

@Mapper
public interface OrderItemMapper {
    long selectPrice(String itemCode);

    int insertItems(List<OrderItemVO> items);

    int deleteItems(String orderNo);
}
