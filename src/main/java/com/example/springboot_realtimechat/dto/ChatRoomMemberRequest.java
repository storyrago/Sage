package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomMemberRequest {
    @NotNull
    private Long memberId;
}
