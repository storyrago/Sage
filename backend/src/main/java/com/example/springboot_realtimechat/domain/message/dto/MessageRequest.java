package com.example.springboot_realtimechat.domain.message.dto;

import com.example.springboot_realtimechat.domain.message.service.MessageService;

import jakarta.validation.constraints.Pattern;
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

    // 클라이언트가 전송마다 새로 만드는 UUID. 같은 값으로 다시 보내면 처음 저장된 메시지를 돌려받는다.
    // 없어도 된다(구버전 클라이언트). 형식이 틀리면 거부한다.
    @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "clientMessageId는 UUID 형식이어야 해요.")
    String clientMessageId;
}
