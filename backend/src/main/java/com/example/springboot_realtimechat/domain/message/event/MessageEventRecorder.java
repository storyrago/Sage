package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.message.entity.Message;

/**
 * 메시지 변경 이벤트를 남긴다. 반드시 메시지를 바꾼 트랜잭션 안에서 호출한다 —
 * 그래야 메시지와 이벤트가 함께 커밋되거나 함께 롤백된다.
 */
public interface MessageEventRecorder {

    void record(MessageEventType type, Message message);
}
