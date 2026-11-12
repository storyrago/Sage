package com.example.springboot_realtimechat.domain.chatroom.dto;

import lombok.Getter;

@Getter
public class UnreadEvent {
    private final Long chatroomId;
    private final Long messageId;
    // 방 안 순번. 화면은 방 최신 순번을 이 값으로 올리기만 하므로 같은 알림을 두 번 받아도 숫자가 늘지 않는다.
    private final Long seq;
    // 필드명을 isReplyToMe로 두면 Jackson이 게터의 is 접두사를 떼어 와이어 이름이 어긋난다.
    private final boolean replyToMe;

    public UnreadEvent(Long chatroomId, Long messageId, Long seq, boolean replyToMe) {
        this.chatroomId = chatroomId;
        this.messageId = messageId;
        this.seq = seq;
        this.replyToMe = replyToMe;
    }
}
