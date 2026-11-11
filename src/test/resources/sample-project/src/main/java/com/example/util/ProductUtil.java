package com.example.util;

import com.example.biz.ProductInfoBiz;
import com.example.common.Wcommon;

/** Util 이 빈 이름으로 Biz 를 꺼내 부르는 경우 (UtilBeanTest) */
public class ProductUtil {
    public static String info(String id) {
        ProductInfoBiz biz = (ProductInfoBiz) Wcommon.getBean("productInfoBiz");
        return biz.getInfo(id);
    }

    public static String infoInline(String id) {
        return ((ProductInfoBiz) Wcommon.getBean("productInfoBiz")).getInfo(id);
    }

    public static Object infoReflect(String id) throws Exception {
        Object bean = Wcommon.getBean("productInfoBiz");
        return bean.getClass().getMethod("getInfo", String.class).invoke(bean, id);
    }

    public static String legacy(String id) {
        return ((ProductInfoBiz) Wcommon.getBean("legacyProductBiz")).getInfo(id);
    }

    public static String unknown(String id) {
        return ((ProductInfoBiz) Wcommon.getBean("noSuchBiz")).getInfo(id);
    }

    /** 반환값 없는(void) 메서드도 조사 대상 */
    public static void refresh(String id) {
        ((ProductInfoBiz) Wcommon.getBean("productInfoBiz")).getInfo(id);
    }
}
