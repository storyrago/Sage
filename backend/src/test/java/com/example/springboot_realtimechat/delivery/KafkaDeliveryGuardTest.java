package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.global.kafka.KafkaDeliveryConfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDeliveryGuardTest {

    @Test
    void outbox가_꺼진_채_Kafka_전달을_켜면_기동하지_않는다() {
        // 컨트롤러는 방송을 멈추는데 이벤트도 남지 않으면 메시지가 어디로도 전파되지 않는다.
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaDeliveryConfig.class)
                .withPropertyValues("app.message.delivery=kafka", "app.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("app.outbox.enabled=true");
                });
    }
}
