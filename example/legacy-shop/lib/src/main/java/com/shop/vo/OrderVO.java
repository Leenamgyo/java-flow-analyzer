package com.shop.vo;

public class OrderVO {
    private String orderNo;
    private String memberId;
    private String orderType;
    private String channel;
    private String memo;
    private String couponCode;
    private long totalAmount;
    private String status;
    private String regDate;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public void setChannel(String channel) { this.channel = channel; }
    public void setMemo(String memo) { this.memo = memo; }
    public String getCouponCode() { return couponCode; }
    public void setCouponCode(String couponCode) { this.couponCode = couponCode; }
    public long getTotalAmount() { return totalAmount; }
    public void setTotalAmount(long totalAmount) { this.totalAmount = totalAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public void setRegDate(String regDate) { this.regDate = regDate; }
}
