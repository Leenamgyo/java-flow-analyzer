package com.shop.vo;

public class OrderSearchVO {
    private String status;
    private String memberId;
    private int offset;
    private int limit;
    private String sortKey;
    private String fromDate;

    public void setStatus(String status) { this.status = status; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public void setOffset(int offset) { this.offset = offset; }
    public void setLimit(int limit) { this.limit = limit; }
    public void setSortKey(String sortKey) { this.sortKey = sortKey; }
    public void setFromDate(String fromDate) { this.fromDate = fromDate; }
}
