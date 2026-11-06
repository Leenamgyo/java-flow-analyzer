package com.example.model;

public class SearchVO {
    private String keyword;
    private int page;
    private String sort;
    private String mode;
    private String URL;

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public void setSort(String sort) {
        this.sort = sort;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setURL(String url) {
        this.URL = url;
    }

    public String getKeyword() {
        return keyword;
    }
}
