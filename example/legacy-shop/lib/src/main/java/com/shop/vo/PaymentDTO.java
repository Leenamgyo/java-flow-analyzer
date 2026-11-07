package com.shop.vo;

public class PaymentDTO {
    private String payType;
    private long amount;
    private String cardNo;
    private int installment;
    private String bankCode;
    private String depositor;
    private String approvalNo;

    public String getPayType() { return payType; }
    public void setPayType(String payType) { this.payType = payType; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public void setCardNo(String cardNo) { this.cardNo = cardNo; }
    public void setInstallment(int installment) { this.installment = installment; }
    public void setBankCode(String bankCode) { this.bankCode = bankCode; }
    public void setDepositor(String depositor) { this.depositor = depositor; }
    public String getApprovalNo() { return approvalNo; }
    public void setApprovalNo(String approvalNo) { this.approvalNo = approvalNo; }
}
