package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final long replyCount;
    private final Long lastReadMessageId;

    public UnreadCountResponse(Long chatroomId, long unreadCount, long replyCount, Long lastReadMessageId) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.replyCount = replyCount;
        this.lastReadMessageId = lastReadMessageId;
    }
}
