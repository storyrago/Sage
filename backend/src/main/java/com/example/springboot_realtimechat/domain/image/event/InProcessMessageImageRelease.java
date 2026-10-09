package com.example.springboot_realtimechat.domain.image.event;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 기본 경로. 커밋된 뒤 ImageCleanupListener가 정리한다(롤백되면 정리하지 않는다). */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class InProcessMessageImageRelease implements MessageImageRelease {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void release(String imageUrl) {
        eventPublisher.publishEvent(new ImageDereferencedEvent(imageUrl));
    }
}
