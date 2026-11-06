package com.shop.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockMapper {
    int selectStockForUpdate(String itemCode);

    int updateStock(String itemCode, int stock);
}
