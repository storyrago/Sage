package com.example.springboot_realtimechat.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageRequest {
    // 이미지 전용 메시지는 content가 비어 있으므로 최대 길이만 제한한다.
    // 빈 값 거부는 MessageService.create(EMPTY_MESSAGE)가 맡는다.
    @Size(max = 500, message = "메시지는 500자까지 보낼 수 있어요.")
    String content;
    String imageUrl;
    Long replyToId;
}
