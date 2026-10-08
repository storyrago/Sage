package com.example.springboot_realtimechat.global.kafka;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.event.SchemaRegistryUnavailableException;
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
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

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
     * 같은 자리에서 재시도한 뒤 원본 바이트 그대로 DLT로 보낸다.
     * 재시도 토픽으로 미루면 방 안 순서가 깨지므로 쓰지 않는다.
     * 레지스트리 불통(SchemaRegistryUnavailableException)은 복구될 때까지 그 파티션을 무기한 멈춘다
     * (백오프 상한 30초, max.poll.interval.ms 기본값 5분보다 작다). 해석할 수 없는 이벤트(SerializationException)는
     * 몇 번을 다시 해도 같으므로 재시도 없이 바로 보낸다 — 그 파티션의 다른 방까지 멈추지 않게. 그 밖의 처리 실패는 3회 재시도한다.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(MessageEventSchema.DEAD_LETTER_TOPIC, record.partition()));
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            String group = KafkaUtils.getConsumerGroupId();
            // DLT 발행이 실패하면 예외가 올라가 오프셋을 넘기지 않고 다시 시도한다(잃지 않는 쪽). 로그·카운터는 발행에 성공한 뒤에만 남긴다.
            deadLetters.accept(record, exception);
            log.error("처리하지 못한 이벤트를 DLT로 보낸다: group={}, topic={}, partition={}, offset={}, key={}, eventId={}",
                    group, record.topic(), record.partition(), record.offset(), record.key(), header(record, "id"),
                    exception);
            meterRegistry.counter(DEAD_LETTER_COUNTER, "group", group == null ? "unknown" : group).increment();
        };

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(200L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(SerializationException.class);
        // SchemaRegistryUnavailableException은 SerializationException을 원인으로 감싸고 있다. 분류기가 원인 체인까지
        // 훑으므로, 명시적으로 재시도 대상으로 등록해 두지 않으면 감싸인 SerializationException 분류를 그대로 물려받아
        // DLT로 즉시 보내진다. 레지스트리 장애는 재시도로 나아질 수 있으므로 자기 자신의 분류를 먼저 두어 막는다.
        handler.addRetryableExceptions(SchemaRegistryUnavailableException.class);
        handler.setBackOffFunction((record, exception) -> backOffFor(exception));
        return handler;
    }

    /**
     * 원인 체인에 SchemaRegistryUnavailableException이 있으면 레지스트리가 돌아올 때까지 무기한 재시도하는
     * 백오프를 돌려준다(초기 1초, 배수 2, 최대 간격 30초 — max.poll.interval.ms 기본값 5분보다 작다).
     * 그 밖은 null — DefaultErrorHandler가 기본 백오프(위 3회 재시도)를 쓴다.
     */
    public static BackOff backOffFor(Exception exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SchemaRegistryUnavailableException) {
                ExponentialBackOff unlimited = new ExponentialBackOff(1_000L, 2.0);
                unlimited.setMaxInterval(30_000L);
                return unlimited;
            }
        }
        return null;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
