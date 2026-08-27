package com.example.springboot_realtimechat.domain.chatroom.dto;

import java.time.LocalDateTime;

public record BannedMemberResponse(Long memberId, String nickname, LocalDateTime bannedAt) {
}
