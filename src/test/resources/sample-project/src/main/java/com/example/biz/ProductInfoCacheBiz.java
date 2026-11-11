package com.example.biz;

import org.springframework.stereotype.Service;

/** 같은 인터페이스의 두 번째 구현 — 구현이 둘이라 '유일 구현' 연결은 안 되고, 빈 이름으로만 고를 수 있다 */
@Service("productInfoCacheBiz")
public class ProductInfoCacheBiz implements ProductInfoBiz {
    @Override
    public String getInfo(String id) {
        return "cached";
    }
}
