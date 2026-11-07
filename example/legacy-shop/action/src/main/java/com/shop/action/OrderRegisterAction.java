package com.shop.action;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import com.shop.biz.PointBiz;
import com.shop.common.BaseAction;
import com.shop.common.Constants;
import com.shop.common.OrderException;
import com.shop.common.PaymentException;
import com.shop.common.ValidationException;
import com.shop.service.CouponService;
import com.shop.service.OrderService;
import com.shop.service.PaymentService;
import com.shop.util.DateUtils;
import com.shop.util.ParamUtil;
import com.shop.util.StringUtil;
import com.shop.validator.OrderValidator;
import com.shop.vo.DeliveryVO;
import com.shop.vo.MemberVO;
import com.shop.vo.NotifyCommand;
import com.shop.vo.OrderResultDTO;
import com.shop.vo.OrderItemVO;
import com.shop.vo.OrderVO;
import com.shop.vo.PaymentDTO;

/**
 * 주문 등록/수정 Action (Struts 1 스타일).
 * 요청 파라미터 → OrderVO / OrderItemVO / DeliveryVO / PaymentDTO 바인딩 → 쿠폰·포인트·결제 → 주문 저장.
 */
public class OrderRegisterAction extends BaseAction {

    private static final String PARAM_PAY_TYPE = "payType";
    private static final int MAX_ITEMS = 50;

    private OrderService orderService;
    private PaymentService paymentService;
    private CouponService couponService;
    private PointBiz pointBiz;

    public ActionForward execute(ActionMapping mapping, ActionForm form,
                                 HttpServletRequest request, HttpServletResponse response) throws Exception {
        MemberVO member = checkLogin(request);
        String mode = ParamUtil.getString(request, Constants.PARAM_MODE, "NEW");
        String orderNo = request.getParameter(Constants.PARAM_ORDER_NO);
        boolean agree = ParamUtil.getBoolean(request, "agreeTerms", false);

        if (!agree) {
            request.setAttribute("error", "약관 동의 필요");
            return mapping.findForward("input");
        }

        OrderVO order = new OrderVO();
        order.setMemberId(member.getMemberId());
        order.setOrderType(mode);
        order.setChannel(request.getParameter("channel"));
        order.setMemo(StringUtil.trimToEmpty(request.getParameter("memo")));
        if ("MODIFY".equals(mode)) {
            if (StringUtil.isEmpty(orderNo)) {
                throw new ValidationException(Constants.PARAM_ORDER_NO, "orderNo required for MODIFY");
            }
            order.setOrderNo(orderNo);
        }

        List<OrderItemVO> items = readItems(request);
        DeliveryVO delivery = readDelivery(request, member);
        PaymentDTO payment = new PaymentDTO();
        String payType = request.getParameter(PARAM_PAY_TYPE);
        long usePoint = ParamUtil.getLong(request, "usePoint", 0L);
        String couponCode = request.getParameter("couponCode");

        try {
            OrderValidator.validate(order, items, delivery);
            long total = calculateTotal(items);
            if (couponCode != null && !couponCode.isEmpty()) {
                total = total - couponService.discount(member.getMemberId(), couponCode, total);
                order.setCouponCode(couponCode);
            }
            if (usePoint > 0) {
                pointBiz.checkBalance(member.getMemberId(), usePoint);
                total -= usePoint;
            }
            order.setTotalAmount(total);
            preparePayment(payment, payType, total, request);

            switch (payType) {
                case "CARD":
                case "BANK":
                    paymentService.approve(payment);
                    break;
                case "POINT":
                    if (usePoint < total) {
                        throw new PaymentException("P001", "포인트 부족");
                    }
                    break;
                default:
                    throw new ValidationException(PARAM_PAY_TYPE, "지원하지 않는 결제수단: " + payType);
            }

            String newOrderNo = orderService.registerOrder(order, items, delivery, payment);
            OrderResultDTO result = OrderResultDTO.builder()
                    .orderNo(newOrderNo)
                    .totalAmount(total)
                    .payType(payType)
                    .message("주문 완료")
                    .build();
            request.setAttribute("result", result);
        } catch (ValidationException e) {
            request.setAttribute("fieldError", e.getField());
            request.setAttribute("error", e.getMessage());
            return mapping.findForward("input");
        } catch (PaymentException e) {
            try {
                paymentService.cancel(payment);
            } catch (PaymentException ce) {
                writeErrorLog(member.getMemberId(), "CANCEL_FAIL:" + ce.getCode());
            }
            throw new OrderException(e.getCode(), e.getMessage());
        } finally {
            writeAccessLog(member.getMemberId(), "ORDER_" + mode);
        }

        if (ParamUtil.getBoolean(request, "sendSms", false)) {
            NotifyCommand notify = new NotifyCommand(member.getMemberId(), request.getParameter("smsPhone"));
            request.setAttribute("notify", notify);
            items.forEach(item -> notifyItem(member, item));
        }
        return mapping.findForward("success");
    }

    /** 상품 배열 파라미터 → OrderItemVO 목록 */
    private List<OrderItemVO> readItems(HttpServletRequest request) throws ValidationException {
        String[] codes = request.getParameterValues("itemCode");
        String[] qtys = request.getParameterValues("qty");
        String[] options = request.getParameterValues("optionCode");
        if (codes == null || qtys == null) {
            throw new ValidationException("itemCode", "상품이 없습니다");
        }
        if (codes.length > MAX_ITEMS || codes.length != qtys.length) {
            throw new ValidationException("itemCode", "상품 수량 불일치 또는 최대 " + MAX_ITEMS + "개 초과");
        }
        List<OrderItemVO> items = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            OrderItemVO item = new OrderItemVO();
            item.setSeq(i + 1);
            item.setItemCode(codes[i]);
            item.setQuantity(toQuantity(qtys[i]));
            if (options != null && i < options.length) {
                item.setOptionCode(options[i]);
            }
            items.add(item);
        }
        return items;
    }

    private int toQuantity(String qty) throws ValidationException {
        try {
            return Integer.parseInt(qty);
        } catch (NumberFormatException e) {
            throw new ValidationException("qty", "숫자가 아닙니다: " + qty);
        }
    }

    /** 배송지: 회원 정보 사용 여부에 따라 회원 VO 또는 요청 파라미터에서 채운다 */
    private DeliveryVO readDelivery(HttpServletRequest request, MemberVO member) {
        DeliveryVO d = new DeliveryVO();
        String sameAsMember = request.getParameter("sameAsMember");
        if ("Y".equals(sameAsMember)) {
            d.setReceiverName(member.getName());
            d.setPhone(member.getPhone());
            d.setZipCode(member.getZipCode());
            d.setAddress(member.getAddress());
        } else {
            d.setReceiverName(request.getParameter("receiverName"));
            d.setPhone(normalizePhone(request.getParameter("receiverPhone")));
            d.setZipCode(request.getParameter(Constants.PARAM_ZIP));
            d.setAddress(request.getParameter("addr1") + " " + request.getParameter("addr2"));
        }
        d.setRequestDate(ParamUtil.getDate(request, "deliveryDate", DateUtils.today()));
        d.setMessage(request.getParameter("deliveryMsg"));
        return d;
    }

    private String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.replaceAll("[^0-9]", "");
    }

    private long calculateTotal(List<OrderItemVO> items) {
        long sum = 0;
        for (OrderItemVO item : items) {
            sum += item.getPrice() * item.getQuantity();
        }
        return sum;
    }

    /** 결제 정보: payType 은 execute 에서 읽은 값이 인자로 전달된다 */
    private void preparePayment(PaymentDTO payment, String payType, long amount, HttpServletRequest request) {
        payment.setPayType(payType);
        payment.setAmount(amount);
        if ("CARD".equals(payType)) {
            payment.setCardNo(request.getParameter("cardNo"));
            payment.setInstallment(ParamUtil.getInt(request, "installment", 0));
        } else if ("BANK".equals(payType)) {
            payment.setBankCode(request.getParameter("bankCode"));
            payment.setDepositor(StringUtil.trimToEmpty(request.getParameter("depositor")));
        }
    }

    private void notifyItem(MemberVO member, OrderItemVO item) {
        writeAccessLog(member.getMemberId(), "SMS:" + item.getItemCode());
    }
}
