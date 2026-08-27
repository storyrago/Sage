package com.example.springboot_realtimechat.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NicknameRequest {
    @NotBlank
    private String nickname;
}
