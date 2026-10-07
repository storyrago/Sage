package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.message.entity.Message;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기본값. app.outbox.enabled가 true가 아니면 이벤트를 남기지 않는다(전파는 기존 Redis 경로). */
@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopMessageEventRecorder implements MessageEventRecorder {

    @Override
    public void record(MessageEventType type, Message message) {
    }
}
