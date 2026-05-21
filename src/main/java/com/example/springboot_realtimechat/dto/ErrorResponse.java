package com.example.springboot_realtimechat.dto;

import lombok.Getter;

@Getter
public class ErrorResponse {
    private String message;

    public ErrorResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
