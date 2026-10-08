package com.example.springboot_realtimechat.domain.message.event;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.avro.Schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class MessageEventSchema {

    public static final String AGGREGATE_TYPE = "message";
    // Debezium Outbox Event Router의 route.topic.replacement=chat.${routedByValue}.events와 같은 이름이어야 한다.
    public static final String TOPIC = "chat." + AGGREGATE_TYPE + ".events";
    // TopicNameStrategy(기본값): 값 스키마의 subject는 "<토픽>-value"다.
    public static final String SUBJECT = TOPIC + "-value";
    public static final Schema SCHEMA = load();

    private MessageEventSchema() {
    }

    private static Schema load() {
        try (InputStream in = MessageEventSchema.class.getResourceAsStream("/avro/message-event.avsc")) {
            if (in == null) {
                throw new IllegalStateException("클래스패스에 avro/message-event.avsc가 없다");
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 레지스트리에 이 스키마가 등록돼 있는지 확인하고 스키마 id를 돌려준다.
     * 등록은 배포 단계(infra/local/scripts/register-schema.sh)가 호환성 검사를 거쳐 하고, 앱은 확인만 한다.
     */
    public static int requireRegistered(SchemaRegistryClient client) {
        try {
            return client.getId(SUBJECT, new AvroSchema(SCHEMA), true);
        } catch (IOException | RestClientException e) {
            throw new IllegalStateException(
                    "스키마 레지스트리에 " + SUBJECT + " 스키마가 등록돼 있지 않거나 레지스트리에 접근할 수 없다", e);
        }
    }
}
