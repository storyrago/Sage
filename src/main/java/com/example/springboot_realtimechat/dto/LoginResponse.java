package com.example.springboot_realtimechat.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
public class LoginResponse {
    // Authorization: `${tokenType} ${accessToken}`
    private final String tokenType = "Bearer"; // 프론트에서 Authorization 헤더 만들 때 의미가 명확해짐
    private final String accessToken;

    public LoginResponse(String accessToken) {
        this.accessToken = accessToken;
    }
}
