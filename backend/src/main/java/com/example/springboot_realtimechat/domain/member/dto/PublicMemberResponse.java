package com.example.springboot_realtimechat.domain.member.dto;

import com.example.springboot_realtimechat.domain.member.entity.Member;

import java.time.LocalDateTime;

/** 타인 조회용 회원 응답. 이메일을 싣지 않는다. */
public record PublicMemberResponse(
        Long id,
        String nickname,
        String profileImageUrl,
        LocalDateTime createdAt) {

    public static PublicMemberResponse from(Member member) {
        return new PublicMemberResponse(
                member.getId(),
                member.getNickname(),
                member.getProfileImageUrl(),
                member.getCreatedAt());
    }
}
