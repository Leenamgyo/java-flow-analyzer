package com.example.biz;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import com.example.dao.ScpAppDAO;
import com.example.model.AppListCommand;

@Service
public class ScpAppListBizImpl implements ScpAppListBiz {
    private ScpAppDAO scpAppDAO;

    @Override
    public List<String> getAppList(AppListCommand cmd) {
        return new ArrayList<>(scpAppDAO.selectApps(cmd.getMcc()));
    }
}
