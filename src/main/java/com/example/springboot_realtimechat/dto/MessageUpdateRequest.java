package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageUpdateRequest {
    @NotBlank
    @Size(max = 500, message = "메시지는 500자까지 보낼 수 있어요.")
    private String content;
}
