package com.shop.vo;

public class OrderItemVO {
    private String orderNo;
    private int seq;
    private String itemCode;
    private String optionCode;
    private int quantity;
    private long price;

    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public void setSeq(int seq) { this.seq = seq; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }
    public void setOptionCode(String optionCode) { this.optionCode = optionCode; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
}
