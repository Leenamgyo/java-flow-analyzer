package com.shop.biz;

import com.shop.common.PaymentException;
import com.shop.dao.MemberDAO;

public class PointBiz {
    private MemberDAO memberDAO;

    public void checkBalance(String memberId, long usePoint) throws PaymentException {
        long balance = memberDAO.selectPoint(memberId);
        if (balance < usePoint) {
            throw new PaymentException("P002", "point balance " + balance + " < " + usePoint);
        }
    }
}
