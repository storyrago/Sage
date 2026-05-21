package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageRequest {
    @NotBlank
    String content;
}
