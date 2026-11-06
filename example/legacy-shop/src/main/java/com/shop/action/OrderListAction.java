package com.shop.action;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import com.shop.common.BaseAction;
import com.shop.common.Constants;
import com.shop.common.OrderException;
import com.shop.dao.OrderDAO;
import com.shop.service.OrderService;
import com.shop.util.DateUtils;
import com.shop.util.ParamUtil;

/** 주문 목록 조회 Action (Struts 1 스타일) */
public class OrderListAction extends BaseAction {

    private OrderService orderService;
    private OrderDAO orderDAO;

    public ActionForward execute(ActionMapping mapping, ActionForm form,
                                 HttpServletRequest request, HttpServletResponse response) throws Exception {
        String userId = currentUser(request);
        String status = request.getParameter(Constants.PARAM_STATUS);
        int page = ParamUtil.getInt(request, Constants.PARAM_PAGE, 1);
        int size = ParamUtil.getInt(request, "size", 20);
        String from = ParamUtil.getString(request, "fromDate", DateUtils.today());
        String[] categories = request.getParameterValues("category");
        String sortKey = request.getParameter("sort_" + userId);

        if (size > Constants.MAX_PAGE_SIZE) {
            size = Constants.MAX_PAGE_SIZE;
        }

        List<Map<String, Object>> orders;
        try {
            validate(status, page);
            Map<String, Object> cond = buildCondition(status, from, categories);
            switch (status) {
                case "CANCELED":
                    orders = orderService.findCanceledOrders(cond, page, size);
                    break;
                case "PAID", "SHIPPED":
                    orders = orderService.findOrders(cond, page, size);
                    break;
                default:
                    orders = new ArrayList<>();
            }
            for (Map<String, Object> order : orders) {
                if (order.get("amount") == null) {
                    continue;
                }
                maskPersonalInfo(order);
            }
            request.setAttribute("orders", orders);
        } catch (OrderException e) {
            orderDAO.insertErrorLog(userId, e.getCode());
            request.setAttribute("error", e.getMessage());
            return mapping.findForward("error");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new OrderException("E400", e.getMessage());
        } finally {
            writeAccessLog(userId, "ORDER_LIST");
        }

        if ("excel".equals(request.getParameter("format"))) {
            exportExcel(orders, response);
            return null;
        }
        return mapping.findForward("success");
    }

    private void validate(String status, int page) throws OrderException {
        if (status == null || status.isEmpty()) {
            throw new IllegalArgumentException("status is required");
        }
        if (page < 1) {
            throw new OrderException("E001", "invalid page");
        }
    }

    private Map<String, Object> buildCondition(String status, String from, String[] categories) {
        Map<String, Object> cond = new java.util.HashMap<>();
        cond.put("status", status);
        cond.put("from", DateUtils.parse(from));
        if (categories != null) {
            cond.put("categories", normalize(categories));
        }
        return cond;
    }

    private List<String> normalize(String[] values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            out.add(v.trim().toUpperCase());
        }
        return out;
    }

    void maskPersonalInfo(Map<String, Object> order) {
        order.put("phone", DateUtils.mask(String.valueOf(order.get("phone"))));
    }

    private void exportExcel(List<Map<String, Object>> orders, HttpServletResponse response) throws IOException {
        try (PrintWriter writer = response.getWriter()) {
            for (Map<String, Object> o : orders) {
                writer.println(o);
            }
        } catch (IOException e) {
            orderDAO.insertErrorLog("system", "EXPORT");
            throw e;
        }
    }
}
