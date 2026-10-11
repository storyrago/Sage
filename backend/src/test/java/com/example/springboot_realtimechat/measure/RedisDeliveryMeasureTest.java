package com.example.springboot_realtimechat.measure;

import org.junit.jupiter.api.Tag;
import org.springframework.context.ConfigurableApplicationContext;

/** 기존 경로: 저장 후 Redis Pub/Sub으로 방송. 순서·무손실을 보장하지 않으므로 측정값을 출력만 한다. */
@Tag("measure")
class RedisDeliveryMeasureTest extends DeliveryMeasurement {

    @Override
    String mode() {
        return "redis";
    }

    @Override
    void awaitReady(ConfigurableApplicationContext node) {
        // Redis 구독은 기동 중에 맺어진다. 실제 도착 여부는 워밍업 메시지로 확인한다.
    }

    @Override
    void cutPropagation() throws Exception {
        MeasureEnvironment.cutRedis();
    }

    @Override
    void restorePropagation() throws Exception {
        MeasureEnvironment.restoreRedis();
    }

    @Override
    boolean assertsGuarantees() {
        return false;
    }
}
