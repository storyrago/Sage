package com.example.springboot_realtimechat.domain.message.service;

import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 응답으로 나가는 메시지의 이미지 URL을 서명한다.
 * 만료는 액세스 토큰 수명과 같은 1시간 — 세션이 살아있는 동안 유효하다는 규칙 하나로 맞춘다.
 *
 * MessageResponse를 만드는 유일한 경로다. 서명하지 않고 응답을 만들면 그 경로의 이미지는 보이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MessageResponseFactory {

    public static final Duration IMAGE_URL_TTL = Duration.ofHours(1);

    private final S3Service s3Service;

    public MessageResponse of(Message message) {
        MessageResponse response = MessageResponse.from(message);
        response.setImageUrl(s3Service.presignedGetUrl(response.getImageUrl(), IMAGE_URL_TTL));
        return response;
    }
}
