package com.example.springboot_realtimechat.event;

/** 회원이 방을 나갔다. 그 방의 구독을 회수하는 근거다. */
public record RoomLeftEvent(Long memberId, Long roomId) {
}
