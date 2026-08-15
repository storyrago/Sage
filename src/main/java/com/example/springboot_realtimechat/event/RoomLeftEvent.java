package com.example.springboot_realtimechat.event;

import com.example.springboot_realtimechat.global.exception.ErrorCode;

/** 회원이 방을 나갔다. reason은 구독 회수 통지에 그대로 실린다. 자진 퇴장과 강퇴가 여기서 갈린다. */
public record RoomLeftEvent(Long memberId, Long roomId, ErrorCode reason) {
}
