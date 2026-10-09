package com.example.springboot_realtimechat.domain.image.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 경로. 같은 트랜잭션에서 커밋된 삭제 이벤트(dereferencedImageUrl)를 이미지 정리 소비자가 처리하므로
 * 여기서는 아무것도 하지 않는다(커밋되면 반드시, 롤백되면 전혀).
 */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
public class OutboxMessageImageRelease implements MessageImageRelease {

    @Override
    public void release(String imageUrl) {
    }
}
