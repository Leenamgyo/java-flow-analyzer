package com.shop.vo;

public class DeliveryVO {
    private String orderNo;
    private String receiverName;
    private String phone;
    private String zipCode;
    private String address;
    private String requestDate;
    private String message;

    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public void setRequestDate(String requestDate) { this.requestDate = requestDate; }
    public void setMessage(String message) { this.message = message; }
}
