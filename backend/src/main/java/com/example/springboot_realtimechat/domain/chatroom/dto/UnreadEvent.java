package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;

@Getter
public class UnreadEvent {
    private final Long chatroomId;
    private final Long messageId;
    // 필드명을 isReplyToMe로 두면 Jackson이 게터의 is 접두사를 떼어 와이어 이름이 어긋난다.
    private final boolean replyToMe;

    public UnreadEvent(Long chatroomId, Long messageId, boolean replyToMe) {
        this.chatroomId = chatroomId;
        this.messageId = messageId;
        this.replyToMe = replyToMe;
    }
}
