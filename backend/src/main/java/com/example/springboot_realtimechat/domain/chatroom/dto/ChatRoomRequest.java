package com.example.springboot_realtimechat.domain.chatroom.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomRequest {
    @NotBlank
    private String name;

    @JsonProperty("private")
    private boolean isPrivate;
}
