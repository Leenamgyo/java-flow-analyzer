package com.shop.biz;

import com.shop.common.OrderException;
import com.shop.mapper.StockMapper;

public class StockBizImpl implements StockBiz {
    private static final int MAX_RETRY = 3;
    private StockMapper stockMapper;

    @Override
    public void decrease(String itemCode, int quantity) throws OrderException {
        int retry = 0;
        while (retry < MAX_RETRY) {
            int stock = stockMapper.selectStockForUpdate(itemCode);
            if (stock < quantity) {
                throw new OrderException("S001", "out of stock: " + itemCode);
            }
            if (stockMapper.updateStock(itemCode, stock - quantity) == 1) {
                return;
            }
            retry++;
        }
        throw new OrderException("S002", "stock update conflict: " + itemCode);
    }
}
