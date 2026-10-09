package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;

@Getter
public class UnreadCountResponse {
    private final Long chatroomId;
    private final long unreadCount;
    private final long replyCount;
    // 화면이 순번 기준으로 옮겨 가면 lastReadSeq만 남긴다.
    private final Long lastReadMessageId;
    private final long lastMessageSeq;
    private final long lastReadSeq;

    public UnreadCountResponse(Long chatroomId, long unreadCount, long replyCount, Long lastReadMessageId,
                               long lastMessageSeq, long lastReadSeq) {
        this.chatroomId = chatroomId;
        this.unreadCount = unreadCount;
        this.replyCount = replyCount;
        this.lastReadMessageId = lastReadMessageId;
        this.lastMessageSeq = lastMessageSeq;
        this.lastReadSeq = lastReadSeq;
    }
}
