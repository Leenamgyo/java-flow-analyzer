package com.example.support;

/** 프로젝트 로그 서비스 (logService.info) — 본문을 그리지 않으면 로그 블록, 본문을 그리면(==> 필요) 호출 노드 */
public class LogService {
    public void info(String message) {
        write(message);
    }

    private void write(String message) {
    }
}
