package com.example.springboot_realtimechat.domain.message.delivery;

import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;

/**
 * 커밋된 메시지 변경을 방 전체에 알린다. 컨트롤러는 전파 경로를 모른 채 이것만 부른다.
 * 경로는 app.message.delivery(redis | kafka)로 고른다.
 */
public interface MessageBroadcaster {

    void broadcast(MessageResponse response);
}
