package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final long replyCount;
    private final long lastMessageSeq;
    private final long lastReadSeq;

    public UnreadCountResponse(Long chatroomId, long unreadCount, long replyCount,
                               long lastMessageSeq, long lastReadSeq) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.replyCount = replyCount;
        this.lastMessageSeq = lastMessageSeq;
        this.lastReadSeq = lastReadSeq;
    }
}
