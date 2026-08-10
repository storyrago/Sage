package com.example.springboot_realtimechat.dto;

import java.time.LocalDateTime;

public record BannedMemberResponse(Long memberId, String nickname, LocalDateTime bannedAt) {
}
