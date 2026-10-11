package com.example.springboot_realtimechat.measure;

import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

import java.time.Duration;

/** 새 경로: outbox → Debezium → Kafka → 서버별 소비자. 방 안 순서와 무손실을 보장한다고 주장하므로 단언한다. */
@Tag("measure")
class KafkaDeliveryMeasureTest extends DeliveryMeasurement {

    @Override
    String mode() {
        return "kafka";
    }

    @Override
    void awaitReady(ConfigurableApplicationContext node) {
        KafkaListenerEndpointRegistry registry = node.getBean(KafkaListenerEndpointRegistry.class);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() ->
                registry.getListenerContainer("realtimeDelivery").getAssignedPartitions().size()
                        == CdcTestEnvironment.TOPIC_PARTITIONS);
    }

    @Override
    void cutPropagation() throws Exception {
        CdcTestEnvironment.pauseConnector();
    }

    @Override
    void restorePropagation() throws Exception {
        CdcTestEnvironment.resumeConnector();
    }

    @Override
    boolean assertsGuarantees() {
        return true;
    }
}
