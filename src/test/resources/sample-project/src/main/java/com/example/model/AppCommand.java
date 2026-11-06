package com.example.model;

public class AppCommand {
    private String appId;
    private String shopId;
    public String memo;
    private String extra;
    private String createdAt = "NOW";

    public AppCommand(String appId, String shopId) {
        this.appId = appId;
        this.shopId = shopId;
    }

    public void setShopId(String shopId) {
        this.shopId = shopId;
    }
}
