package com.example.biz;

import org.springframework.stereotype.Service;
import com.example.dao.ProductDAO;

@Service("productInfoBiz")
public class ProductInfoBizImpl implements ProductInfoBiz {
    private ProductDAO productDAO;

    @Override
    public String getInfo(String id) {
        return productDAO.select(id);
    }
}
