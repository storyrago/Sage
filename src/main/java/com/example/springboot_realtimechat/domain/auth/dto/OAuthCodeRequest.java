package com.example.springboot_realtimechat.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OAuthCodeRequest {
    @NotBlank
    String code;
}
