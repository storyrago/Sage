package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.message.delivery.RealtimeMessageDelivery;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeMessageDeliveryTest {

    @Test
    void 이벤트를_방_구독_주소로_보낸다() {
        MessageEventReader reader = mock(MessageEventReader.class);
        MessageResponseFactory factory = mock(MessageResponseFactory.class);
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        byte[] payload = {0, 1};
        MessageEvent event = MessageEventFixtures.event(MessageEventType.CREATED, 7L, 11L, 3L, 1L, null, false);
        MessageResponse response = mock(MessageResponse.class);
        when(reader.read(payload)).thenReturn(event);
        when(factory.of(event)).thenReturn(response);

        new RealtimeMessageDelivery(reader, factory, template)
                .onEvent(new ConsumerRecord<>("chat.message.events", 0, 0L, "7", payload));

        verify(template).convertAndSend("/sub/chatrooms/7", response);
    }
}
