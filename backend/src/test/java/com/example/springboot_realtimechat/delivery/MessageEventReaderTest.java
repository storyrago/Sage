package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.message.event.MessageEventReader;
import com.example.springboot_realtimechat.domain.message.event.SchemaRegistryUnavailableException;
import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;
import com.example.springboot_realtimechat.outbox.MessageEventTestSupport;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageEventReaderTest {

    private static final String SCOPE = "reader-test";

    private MessageEventReader reader() {
        return new MessageEventReader(new KafkaAvroDeserializer(
                MockSchemaRegistry.getClientForScope(SCOPE), KafkaDeliveryConfig.deserializerConfig("mock://" + SCOPE)));
    }

    /** 레지스트리에 못 닿는 상황을 흉내낸다. close/ticker만 정상 동작하고, 나머지 호출은 전부 레지스트리 장애로 실패한다. */
    private MessageEventReader readerBackedByFailingRegistry(Exception registryFailure) {
        SchemaRegistryClient failing = (SchemaRegistryClient) Proxy.newProxyInstance(
                SchemaRegistryClient.class.getClassLoader(),
                new Class[]{SchemaRegistryClient.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if (method.getName().equals("ticker")) {
                        return com.google.common.base.Ticker.systemTicker();
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw registryFailure;
                });
        return new MessageEventReader(new KafkaAvroDeserializer(
                failing, KafkaDeliveryConfig.deserializerConfig("mock://" + SCOPE)));
    }

    @Test
    void 레지스트리_와이어_포맷을_생성_클래스로_읽는다() {
        MessageEventTestSupport.register(SCOPE);
        MessageEvent event = MessageEventFixtures.event(MessageEventType.CREATED, 7L, 11L, 3L, 1L, null, false);

        MessageEvent read = reader().read(MessageEventTestSupport.serialize(SCOPE, event));

        assertThat(read).isEqualTo(event);
    }

    @Test
    void 해석할_수_없는_바이트는_직렬화_예외다() {
        // 이 예외는 재시도 없이 DLT로 간다(KafkaDeliveryConfig).
        assertThatThrownBy(() -> reader().read(new byte[]{1, 2, 3}))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void 레지스트리에_연결이_거부되면_레지스트리_불통_예외다() {
        // 실제 연결 거부는 IOException 전체가 아니라 java.net.ConnectException(SocketException)으로 온다.
        MessageEventTestSupport.register(SCOPE);
        byte[] payload = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));

        assertThatThrownBy(() -> readerBackedByFailingRegistry(new ConnectException("연결할 수 없음")).read(payload))
                .isInstanceOf(SchemaRegistryUnavailableException.class)
                .hasCauseInstanceOf(SerializationException.class);
    }

    @Test
    void 레지스트리가_429로_응답하면_레지스트리_불통_예외다() {
        // Confluent toKafkaException이 429를 ThrottlingQuotaExceededException(RetriableException)으로 매핑한다.
        MessageEventTestSupport.register(SCOPE);
        byte[] payload = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));

        assertThatThrownBy(() -> readerBackedByFailingRegistry(new RestClientException("과다 요청", 429, 42901))
                .read(payload))
                .isInstanceOf(SchemaRegistryUnavailableException.class);
    }

    @Test
    void 몸체가_잘린_바이트는_레지스트리_불통이_아니라_직렬화_예외다() {
        // 레지스트리는 정상 응답했지만 Avro 본문이 잘려 EOFException(IOException의 하위)으로 깨진다.
        // 이벤트 자체가 손상된 것이라 재시도해도 나아지지 않으므로 DLT로 바로 가야 한다.
        MessageEventTestSupport.register(SCOPE);
        byte[] valid = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 4);

        assertThatThrownBy(() -> reader().read(truncated))
                .isInstanceOf(SerializationException.class)
                .isNotInstanceOf(SchemaRegistryUnavailableException.class);
    }

    @Test
    void 몸체가_깨진_바이트도_레지스트리_불통이_아니라_직렬화_예외다() {
        // 와이어 포맷의 스키마 id 뒤 바이트를 뭉개면 varint 디코딩이 깨진다(InvalidNumberEncodingException 등).
        MessageEventTestSupport.register(SCOPE);
        byte[] valid = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));
        byte[] garbled = Arrays.copyOf(valid, valid.length);
        for (int i = 5; i < garbled.length; i++) {
            garbled[i] = (byte) 0xFF;
        }

        assertThatThrownBy(() -> reader().read(garbled))
                .isInstanceOf(SerializationException.class)
                .isNotInstanceOf(SchemaRegistryUnavailableException.class);
    }

    @Test
    void 레지스트리가_5xx로_응답하면_레지스트리_불통_예외다() {
        MessageEventTestSupport.register(SCOPE);
        byte[] payload = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));

        assertThatThrownBy(() -> readerBackedByFailingRegistry(new RestClientException("서버 오류", 500, 50001))
                .read(payload))
                .isInstanceOf(SchemaRegistryUnavailableException.class);
    }

    @Test
    void 레지스트리가_404로_응답하면_직렬화_예외다() {
        // 스키마가 없다는 뜻이라 재시도해도 나아지지 않는다 — DLT로 바로 간다.
        MessageEventTestSupport.register(SCOPE);
        byte[] payload = MessageEventTestSupport.serialize(SCOPE,
                MessageEventFixtures.event(MessageEventType.CREATED, 1L, 2L, 3L, 1L, null, false));

        assertThatThrownBy(() -> readerBackedByFailingRegistry(new RestClientException("없음", 404, 40401))
                .read(payload))
                .isInstanceOf(SerializationException.class)
                .isNotInstanceOf(SchemaRegistryUnavailableException.class);
    }
}
