package com.shop.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper {
    List<Map<String, Object>> selectOrders(Map<String, Object> cond, int offset, int limit);
}
