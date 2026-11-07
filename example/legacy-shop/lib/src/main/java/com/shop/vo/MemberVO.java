package com.shop.vo;

public class MemberVO {
    private String memberId;
    private String name;
    private String phone;
    private String zipCode;
    private String address;
    private String grade;

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getZipCode() { return zipCode; }
    public void setZipCode(String zipCode) { this.zipCode = zipCode; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
}
