package com.example.springboot_realtimechat.global.websocket;

/** 개인 오류 채널(/user/queue/errors) 페이로드. destination으로 어느 구독이 거부됐는지 특정한다. */
public record WsErrorResponse(String code, String message, String destination) {
}
