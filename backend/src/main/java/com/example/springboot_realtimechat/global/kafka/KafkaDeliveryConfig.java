package com.example.springboot_realtimechat.global.kafka;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.global.outbox.OutboxProperties;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.KafkaUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** 메시지 전파를 Kafka 경로로 돌렸을 때만 켜지는 소비 기반(노드 id, 역직렬화, 실패 처리). */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
public class KafkaDeliveryConfig {

    public static final String DEAD_LETTER_COUNTER = "sage.kafka.dead.letters";

    public KafkaDeliveryConfig(@Value("${app.outbox.enabled:false}") boolean outboxEnabled) {
        // Kafka 경로에서는 컨트롤러가 방송하지 않는다. outbox까지 꺼져 있으면 메시지가 어디로도 전파되지 않는다.
        if (!outboxEnabled) {
            throw new IllegalStateException("app.message.delivery=kafka에는 app.outbox.enabled=true가 필요하다");
        }
    }

    @Bean
    public MessageDeliveryNode messageDeliveryNode(@Value("${app.message.node-id:}") String nodeId) {
        // 비어 있으면 기동마다 새 값 → 재기동한 서버는 새 group으로 최신 위치부터 읽는다.
        return new MessageDeliveryNode(nodeId.isBlank() ? UUID.randomUUID().toString() : nodeId);
    }

    @Bean
    public KafkaAvroDeserializer messageEventAvroDeserializer(SchemaRegistryClient outboxSchemaRegistryClient,
                                                              OutboxProperties properties) {
        return new KafkaAvroDeserializer(outboxSchemaRegistryClient, deserializerConfig(properties.schemaRegistryUrl()));
    }

    /** 운영과 테스트가 같은 역직렬화기 설정을 쓰도록 한곳에 둔다. */
    public static Map<String, Object> deserializerConfig(String schemaRegistryUrl) {
        return Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                // 쓴 쪽 스키마(레지스트리)에서 읽는 쪽 스키마(생성 클래스)로 해석한다. 새 필드는 기본값으로 채워진다.
                KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
    }

    /**
     * 같은 자리에서 짧게 재시도한 뒤 원본 바이트 그대로 DLT로 보낸다.
     * 재시도 토픽으로 미루면 방 안 순서가 깨지므로 쓰지 않는다. 재시도 동안 그 파티션은 최대 약 1.4초 멈춘다.
     * 해석할 수 없는 이벤트는 몇 번을 다시 해도 같으므로 바로 보낸다 — 그 파티션의 다른 방까지 멈추지 않게.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(MessageEventSchema.DEAD_LETTER_TOPIC, record.partition()));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            String group = KafkaUtils.getConsumerGroupId();
            log.error("처리하지 못한 이벤트를 DLT로 보낸다: group={}, topic={}, partition={}, offset={}, key={}, eventId={}",
                    group, record.topic(), record.partition(), record.offset(), record.key(), header(record, "id"),
                    exception);
            // DLT 발행이 실패하면 예외가 올라가 오프셋을 넘기지 않고 다시 시도한다(잃지 않는 쪽).
            deadLetters.accept(record, exception);
            meterRegistry.counter(DEAD_LETTER_COUNTER, "group", group == null ? "unknown" : group).increment();
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(200L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(SerializationException.class);
        return handler;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
