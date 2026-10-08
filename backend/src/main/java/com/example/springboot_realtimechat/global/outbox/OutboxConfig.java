package com.example.springboot_realtimechat.global.outbox;

import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;

import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 스위치가 켜졌을 때만 레지스트리 클라이언트와 직렬화기를 만든다. 꺼져 있으면 레지스트리에 접속하지 않는다. */
@Configuration
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    @Bean
    public SchemaRegistryClient outboxSchemaRegistryClient(OutboxProperties properties) {
        // 팩토리는 http(s) 주소와 테스트용 mock://<scope> 주소를 모두 받는다.
        return SchemaRegistryClientFactory.newClient(
                List.of(properties.schemaRegistryUrl()), 100, List.of(new AvroSchemaProvider()), Map.of(), Map.of());
    }

    @Bean
    public KafkaAvroSerializer outboxAvroSerializer(SchemaRegistryClient outboxSchemaRegistryClient,
                                                    OutboxProperties properties) {
        // 기동 시 확인한다. 스키마가 없거나 레지스트리가 불통이면 첫 메시지가 아니라 기동이 실패하고,
        // 확인으로 캐시된 스키마 id 덕분에 이후 쓰기는 레지스트리에 다시 묻지 않는다.
        MessageEventSchema.requireRegistered(outboxSchemaRegistryClient);
        return new KafkaAvroSerializer(outboxSchemaRegistryClient, serializerConfig(properties.schemaRegistryUrl()));
    }

    /** 운영과 테스트가 같은 직렬화기 설정을 쓰도록 한곳에 둔다. */
    public static Map<String, Object> serializerConfig(String schemaRegistryUrl) {
        return Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl,
                // 운영 권장: 스키마는 배포 단계에서 호환성 검사를 거쳐 등록하고, 앱은 등록된 스키마만 쓴다.
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, false,
                AbstractKafkaSchemaSerDeConfig.NORMALIZE_SCHEMAS, true,
                // 생성 클래스의 스키마에 붙는 avro.java.string 속성을 떼어 낸다. 남기면 등록된 스키마와 달라 찾지 못한다.
                KafkaAvroSerializerConfig.AVRO_REMOVE_JAVA_PROPS_CONFIG, true);
    }
}
