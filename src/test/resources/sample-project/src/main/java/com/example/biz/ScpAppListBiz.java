package com.example.biz;

import java.util.List;
import com.example.model.AppListCommand;

public interface ScpAppListBiz {
    List<String> getAppList(AppListCommand cmd);
}
