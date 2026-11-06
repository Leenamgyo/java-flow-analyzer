package com.example.web;

import org.springframework.web.bind.annotation.*;
import com.example.biz.ScpAppListBiz;
import com.example.model.AppListCommand;

@RestController
public class AppController {
    private ScpAppListBiz scpAppListBiz;

    @GetMapping("/apps/{category}")
    public Object list(@PathVariable("category") String category,
                       @RequestParam(value = "mcc", required = false, defaultValue = "450") String mcc,
                       @RequestParam int page,
                       @RequestHeader("X-Device") String device,
                       @ModelAttribute AppListCommand cmd) {
        return scpAppListBiz.getAppList(cmd);
    }
}
