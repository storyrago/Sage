package com.example.springboot_realtimechat.domain.image.event;

import com.example.springboot_realtimechat.domain.image.service.OrphanImageTagger;
import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 메시지 삭제로 참조가 끊긴 이미지에 orphan 태그를 단다(삭제는 버킷 수명주기 규칙이 한다).
 * 서버 전체에서 한 번이면 되므로 단일 group이다. 태깅 직전에 DB의 잔여 참조를 다시 보므로 같은 이벤트가 두 번 와도 무해하다.
 * 방 안 순서가 필요 없어, 실패한 이벤트는 재시도 토픽으로 미루고 파티션은 계속 흘려보낸다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
@RequiredArgsConstructor
public class MessageImageCleanupConsumer {

    public static final String GROUP_ID = "sage-image-cleanup";
    public static final String RETRY_TOPIC_SUFFIX = ".image-cleanup-retry";
    public static final String DLT_TOPIC_SUFFIX = ".image-cleanup-DLT";
    public static final List<String> RETRY_TOPICS = List.of(
            MessageEventSchema.TOPIC + RETRY_TOPIC_SUFFIX + "-0",
            MessageEventSchema.TOPIC + RETRY_TOPIC_SUFFIX + "-1",
            MessageEventSchema.TOPIC + RETRY_TOPIC_SUFFIX + "-2");
    public static final String DEAD_LETTER_TOPIC = MessageEventSchema.TOPIC + DLT_TOPIC_SUFFIX;

    private final MessageEventReader messageEventReader;
    private final OrphanImageTagger orphanImageTagger;
    private final MeterRegistry meterRegistry;

    /**
     * 첫 시도 + 재시도 토픽 3단계(기본 5초·30초·3분) 뒤 DLT. 해석할 수 없는 이벤트는 몇 번을 다시 해도 같으므로 바로 DLT.
     * 레지스트리 불통은 재시도 토픽으로 넘기지 않고 그 자리에서 기다린다(KafkaRetryTopicConfig).
     * 토픽은 배포 단계(create-topics.sh)가 만든다. 이 group은 서버가 바뀌어도 오프셋이 이어지므로
     * 처음 생길 때는 보존된 이벤트를 처음부터 읽는다 — 멱등이라 안전하고, latest면 전환 직후의 삭제를 놓친다.
     */
    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(
                    delayString = "${app.image-cleanup.retry.delay-ms:5000}",
                    multiplierString = "${app.image-cleanup.retry.multiplier:6}",
                    maxDelayString = "${app.image-cleanup.retry.max-delay-ms:180000}"),
            retryTopicSuffix = RETRY_TOPIC_SUFFIX,
            dltTopicSuffix = DLT_TOPIC_SUFFIX,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            autoCreateTopics = "false",
            kafkaTemplate = "kafkaTemplate",
            exclude = SerializationException.class)
    @KafkaListener(id = "imageCleanup", topics = MessageEventSchema.TOPIC, groupId = GROUP_ID,
            properties = "auto.offset.reset=earliest")
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        MessageEvent event = messageEventReader.read(record.value());
        String url = event.getDereferencedImageUrl();
        if (event.getEventType() != MessageEventType.DELETED || url == null) {
            return;
        }
        orphanImageTagger.tagIfUnreferenced(url);
    }

    @DltHandler
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        log.error("이미지 정리에 실패한 이벤트가 DLT에 도착했다: topic={}, partition={}, offset={}, key={}, eventId={}, exception={}",
                record.topic(), record.partition(), record.offset(), record.key(), header(record, "id"),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
        meterRegistry.counter(KafkaDeliveryConfig.DEAD_LETTER_COUNTER, "group", GROUP_ID).increment();
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
