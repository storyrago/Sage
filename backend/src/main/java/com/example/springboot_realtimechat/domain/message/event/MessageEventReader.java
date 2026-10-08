package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.events.MessageEvent;

import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** 토픽에서 받은 바이트(레지스트리 와이어 포맷)를 MessageEvent로 읽는다. 소비자들이 공유한다. */
@Component
@ConditionalOnProperty(name = "app.message.delivery", havingValue = "kafka")
@RequiredArgsConstructor
public class MessageEventReader {

    private final KafkaAvroDeserializer messageEventAvroDeserializer;

    /**
     * 레지스트리에 닿지 못해 못 읽으면 SchemaRegistryUnavailableException — 레지스트리가 돌아올 때까지 다시 시도한다.
     * 그 밖의 해석 불가(매직 바이트 불일치, 스키마 없음 등)는 SerializationException — 재시도 없이 DLT로 간다.
     */
    public MessageEvent read(byte[] payload) {
        Object value;
        try {
            value = messageEventAvroDeserializer.deserialize(MessageEventSchema.TOPIC, payload);
        } catch (SerializationException e) {
            throw asRegistryUnavailableIfApplicable(e);
        }
        if (value instanceof MessageEvent event) {
            return event;
        }
        throw new SerializationException("MessageEvent가 아닌 값: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 원인 체인에 네트워크 장애(접속 불가·타임아웃·5xx 등)나 인증 실패(401·403)가 있을 때만 레지스트리 불통으로 다시 던진다.
     * Avro 본문 손상(잘린 페이로드의 EOFException, 깨진 varint의 InvalidNumberEncodingException 등)도
     * IOException 계열이라 전부 IOException으로 잡으면 손상된 이벤트가 영원히 재시도된다 — 반드시 허용 목록으로 좁힌다.
     */
    private static RuntimeException asRegistryUnavailableIfApplicable(SerializationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (isRegistryUnavailable(cause)) {
                return new SchemaRegistryUnavailableException("레지스트리에 닿지 못했다", e);
            }
        }
        return e;
    }

    private static boolean isRegistryUnavailable(Throwable cause) {
        if (cause instanceof RetriableException) {
            // Confluent toKafkaException: 408/500/503/504 → TimeoutException, 502 → DisconnectException,
            // 429 → ThrottlingQuotaExceededException. 전부 RetriableException 하위.
            return true;
        }
        if (cause instanceof RestClientException restClientException) {
            // 실제 호출 경로(subject.name.strategy=AssociatedNameStrategy의 getAssociationsByResourceName)는
            // RestClientException을 toKafkaException 없이 그대로 감싸 올린다 — 429/408도 ThrottlingQuotaExceededException/
            // TimeoutException으로 바뀌지 않고 RestClientException 그대로 온다. 그래서 상태코드로 직접 판정한다.
            // 401·403은 자격 증명 문제라 이벤트 잘못이 아니다 — 모든 이벤트를 DLT로 쏟지 않고 멈춰서 드러낸다.
            int status = restClientException.getStatus();
            return status >= 500 || status == 429 || status == 408 || status == 401 || status == 403;
        }
        if (cause instanceof AuthenticationException || cause instanceof AuthorizationException) {
            // toKafkaException을 거치는 경로에서는 401·403이 이 둘로 바뀌어 온다.
            return true;
        }
        // 연결 거부(ConnectException)는 SocketException, 호스트를 못 찾으면 UnknownHostException,
        // 읽기 타임아웃은 SocketTimeoutException — 전부 네트워크 장애. EOFException 등 본문 손상은 포함하지 않는다.
        return cause instanceof SocketException
                || cause instanceof UnknownHostException
                || cause instanceof SocketTimeoutException;
    }
}
