package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.delivery.MessageEventFixtures;
import com.example.springboot_realtimechat.domain.image.event.MessageImageCleanupConsumer;
import com.example.springboot_realtimechat.domain.image.service.OrphanImageTagger;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageImageCleanupConsumerTest {

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/rooms/1/00000000-0000-0000-0000-0000000000e1_a.png";
    private static final byte[] PAYLOAD = {0, 1, 2};

    private final MessageEventReader reader = mock(MessageEventReader.class);
    private final OrphanImageTagger tagger = mock(OrphanImageTagger.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MessageImageCleanupConsumer consumer = new MessageImageCleanupConsumer(reader, tagger, meters);

    private static ConsumerRecord<String, byte[]> record() {
        return new ConsumerRecord<>(MessageEventSchema.TOPIC, 0, 0L, "10", PAYLOAD);
    }

    private void receive(MessageEvent event) {
        when(reader.read(PAYLOAD)).thenReturn(event);
        consumer.onEvent(record());
    }

    private static MessageEvent event(MessageEventType type, boolean deleted, String dereferencedImageUrl) {
        MessageEvent event = MessageEventFixtures.event(type, 10L, 50L, 1L, 1L, null, deleted);
        event.setDereferencedImageUrl(dereferencedImageUrl);
        return event;
    }

    @Test
    void 이미지가_끊긴_삭제_이벤트면_잔여_참조를_확인해_태깅한다() {
        receive(event(MessageEventType.DELETED, true, URL));

        verify(tagger).tagIfUnreferenced(URL);
    }

    @Test
    void 끊긴_이미지가_없는_삭제_이벤트는_건너뛴다() {
        receive(event(MessageEventType.DELETED, true, null));

        verify(tagger, never()).tagIfUnreferenced(anyString());
    }

    @Test
    void 생성_수정_이벤트는_건너뛴다() {
        receive(event(MessageEventType.CREATED, false, URL));
        receive(event(MessageEventType.UPDATED, false, URL));

        verify(tagger, never()).tagIfUnreferenced(anyString());
    }

    @Test
    void 정리에_실패하면_예외를_올려_재시도_토픽으로_넘긴다() {
        when(reader.read(PAYLOAD)).thenReturn(event(MessageEventType.DELETED, true, URL));
        doThrow(new RuntimeException("S3 불통")).when(tagger).tagIfUnreferenced(URL);

        assertThatThrownBy(() -> consumer.onEvent(record())).hasMessage("S3 불통");
    }

    @Test
    void DLT에_도착한_건수를_이미지_정리_group으로_센다() {
        consumer.onDeadLetter(record());

        assertThat(meters.get(KafkaDeliveryConfig.DEAD_LETTER_COUNTER)
                .tag("group", MessageImageCleanupConsumer.GROUP_ID).counter().count()).isEqualTo(1.0);
    }
}
