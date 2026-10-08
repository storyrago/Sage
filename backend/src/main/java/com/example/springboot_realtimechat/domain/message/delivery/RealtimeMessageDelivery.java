package com.example.springboot_realtimechat.domain.message.delivery;

import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.events.MessageEvent;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 메시지 이벤트를 이 서버에 접속한 방 구독자에게 보낸다.
 * group이 서버마다 달라 모든 서버가 모든 이벤트를 받는다 — 내장 STOMP 브로커는 자기 서버의 세션에만 배달하기 때문이다.
 * 같은 방은 같은 파티션이고 한 파티션은 한 스레드가 차례로 처리하므로, 방 안에서는 순번 순서로 나간다.
 * 생성·수정·삭제 모두 메시지 전체 상태를 보내고 프론트는 id로 덮어쓰므로, 같은 이벤트가 두 번 와도 무해하다.
 */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
@RequiredArgsConstructor
public class RealtimeMessageDelivery {

    private final MessageEventReader messageEventReader;
    private final MessageResponseFactory messageResponseFactory;
    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(id = "realtimeDelivery", topics = MessageEventSchema.TOPIC,
            groupId = "sage-realtime-#{@messageDeliveryNode.id()}")
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        MessageEvent event = messageEventReader.read(record.value());
        messagingTemplate.convertAndSend("/sub/chatrooms/" + event.getChatroomId(), messageResponseFactory.of(event));
    }
}
