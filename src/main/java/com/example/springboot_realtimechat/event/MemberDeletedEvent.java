package com.example.springboot_realtimechat.event;

/** 회원이 탈퇴했다. 그 회원의 모든 방 구독을 회수하는 근거다. */
public record MemberDeletedEvent(Long memberId) {
}
