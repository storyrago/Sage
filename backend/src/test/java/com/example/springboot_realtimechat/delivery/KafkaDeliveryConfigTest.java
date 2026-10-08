package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.domain.message.event.SchemaRegistryUnavailableException;
import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;

import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOffExecution;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDeliveryConfigTest {

    /** addNotRetryableExceptions/addRetryableExceptions만 노출하는, 분류기만 떼어 검증하기 위한 손잡이. */
    private static final class ProbeErrorHandler extends DefaultErrorHandler {
        boolean retryable(Throwable t) {
            return getExceptionMatcher().match(t);
        }
    }

    @Test
    void SerializationException을_감싼_레지스트리_불통_예외는_재시도_대상으로_분류된다() {
        // 분류기는 원인 체인까지 훑는다. SchemaRegistryUnavailableException을 명시적으로 등록해 두지 않으면
        // 원인에 있는 SerializationException(재시도 불가 분류)을 그대로 물려받아 즉시 DLT로 가버린다.
        ProbeErrorHandler handler = new ProbeErrorHandler();
        handler.addNotRetryableExceptions(SerializationException.class);
        handler.addRetryableExceptions(SchemaRegistryUnavailableException.class);

        SchemaRegistryUnavailableException wrapping =
                new SchemaRegistryUnavailableException("닿지 못함", new SerializationException("해석 실패"));

        assertThat(handler.retryable(wrapping)).isTrue();
        assertThat(handler.retryable(new SerializationException("그냥 해석 불가"))).isFalse();
    }

    @Test
    void 레지스트리_불통_예외는_상한_30초짜리_무기한_백오프다() {
        BackOffExecution execution = KafkaDeliveryConfig
                .backOffFor(new SchemaRegistryUnavailableException("닿지 못함", new IOException("연결 실패")))
                .start();

        for (int i = 0; i < 20; i++) {
            long next = execution.nextBackOff();
            assertThat(next).isNotEqualTo(BackOffExecution.STOP);
            assertThat(next).isLessThanOrEqualTo(30_000L);
        }
    }

    @Test
    void 원인_체인에_감싸인_레지스트리_불통_예외도_무기한_백오프다() {
        Exception wrapped = new RuntimeException("리스너 호출 실패",
                new SchemaRegistryUnavailableException("닿지 못함", new SerializationException("해석 실패")));

        BackOffExecution execution = KafkaDeliveryConfig.backOffFor(wrapped).start();

        assertThat(execution.nextBackOff()).isNotEqualTo(BackOffExecution.STOP);
    }

    @Test
    void 그_밖의_예외는_기본_백오프를_쓰도록_null이다() {
        assertThat(KafkaDeliveryConfig.backOffFor(new SerializationException("해석 불가"))).isNull();
        assertThat(KafkaDeliveryConfig.backOffFor(new RuntimeException("그냥 버그"))).isNull();
    }
}
