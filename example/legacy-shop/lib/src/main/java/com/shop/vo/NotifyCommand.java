package com.shop.vo;

/** SMS 발송 요청 커맨드 */
public class NotifyCommand {
    private final String memberId;
    private final String phone;
    private String template = "ORDER_DONE";

    public NotifyCommand(String memberId, String phone) {
        this.memberId = memberId;
        this.phone = phone;
    }
}
