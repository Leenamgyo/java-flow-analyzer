package com.example.action;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.example.biz.ScpAppListBiz;
import com.example.dao.ScpAppDAO;
import com.example.exception.BizException;
import com.example.model.AppListCommand;
import com.example.util.ParameterUtil;
import com.example.util.RequestUtil;

public class ScpAppListAction extends BaseAction {

    private static final String PARAM_DEVICE = "deviceId";

    private ScpAppListBiz scpAppListBiz;
    private ScpAppDAO scpAppDAO;

    public String execute(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String mcc = request.getParameter("mcc");
        String[] appIds = request.getParameterValues("appId");
        Map<String, String[]> all = request.getParameterMap();
        String deviceId = RequestUtil.getString(request, PARAM_DEVICE, "none");
        int startNum = ParameterUtil.getInt(request, "startNum", 0);
        String dynamic = request.getParameter("prefix_" + mcc);
        String locale = request.getParameter(PARAM_LOCALE);

        validateParameters(request);
        String userId = getLoginUserId(request);

        try {
            AppListCommand cmd = createCommand(mcc, startNum);
            List<String> apps = scpAppListBiz.getAppList(cmd);
            if (apps.isEmpty()) {
                logEmpty(userId);
            } else if (apps.size() > 100) {
                trim(apps, 100);
            } else {
                audit(userId, apps.size());
            }
            return "success";
        } catch (IllegalArgumentException e) {
            handleValidationError(e);
            throw e;
        } catch (BizException | IllegalStateException e) {
            handleError(e);
            throw new RuntimeException("wrapped", e);
        } finally {
            cleanup();
        }
    }

    private void validateParameters(HttpServletRequest request) {
        if (request.getParameter("mcc") == null) {
            throw new IllegalArgumentException("mcc required");
        }
    }

    private AppListCommand createCommand(String mcc, int startNum) {
        AppListCommand cmd = new AppListCommand();
        cmd.setMcc(parseParameter(mcc));
        cmd.setStartNum(startNum);
        return cmd;
    }

    private String parseParameter(String value) {
        return value == null ? "" : value.trim();
    }

    protected void logEmpty(String userId) {
        audit(userId, 0);
    }

    void audit(String userId, int count) {
        scpAppDAO.insertAudit(userId, count);
    }

    private void trim(List<String> apps, int max) {
        while (apps.size() > max) {
            apps.remove(apps.size() - 1);
        }
    }

    private void handleValidationError(IllegalArgumentException e) {
        scpAppDAO.insertError("VALIDATION", e.getMessage());
    }

    private void handleError(Exception e) {
        scpAppDAO.insertError("SYSTEM", e.getMessage());
    }

    private void cleanup() {
    }
}
