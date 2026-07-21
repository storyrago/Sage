package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class TypingResponse {
    private final Long chatroomId;
    private final Long memberId;
    private final String nickname;
    private final boolean typing;

    public TypingResponse(Long chatroomId, Long memberId, String nickname, boolean typing) {
        this.chatroomId = chatroomId;
        this.memberId = memberId;
        this.nickname = nickname;
        this.typing = typing;
    }
}
