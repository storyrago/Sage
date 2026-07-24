package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class UnreadEvent {
    private final Long chatroomId;
    private final Long messageId;

    public UnreadEvent(Long chatroomId, Long messageId) {
        this.chatroomId = chatroomId;
        this.messageId = messageId;
    }
}
