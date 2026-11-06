package com.shop.biz;

import com.shop.common.OrderException;

public interface StockBiz {
    void decrease(String itemCode, int quantity) throws OrderException;
}
