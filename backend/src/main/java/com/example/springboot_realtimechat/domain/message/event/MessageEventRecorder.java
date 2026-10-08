package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.message.entity.Message;

/**
 * 메시지 변경 이벤트를 남긴다. 반드시 메시지를 바꾼 트랜잭션 안에서 호출한다 —
 * 그래야 메시지와 이벤트가 함께 커밋되거나 함께 롤백된다.
 */
public interface MessageEventRecorder {

    default void record(MessageEventType type, Message message) {
        record(type, message, null);
    }

    /** dereferencedImageUrl: 이 변경으로 참조가 끊긴 이미지(없으면 null). */
    void record(MessageEventType type, Message message, String dereferencedImageUrl);
}
