package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final Long lastReadMessageId;

    public UnreadCountResponse(Long chatroomId, long unreadCount, Long lastReadMessageId) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.lastReadMessageId = lastReadMessageId;
    }
}
