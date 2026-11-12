package com.example.springboot_realtimechat.domain.chatroom.dto;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReadRequest {
    // 여기까지 읽었다는 방 안 순번. 없으면 방의 최신 순번까지 읽은 것으로 본다.
    @PositiveOrZero
    private Long seq;
}
