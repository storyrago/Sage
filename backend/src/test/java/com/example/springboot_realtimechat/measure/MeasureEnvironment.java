package com.example.springboot_realtimechat.measure;

import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 측정 테스트 공용 환경. CDC 컨테이너 4종 위에 Redis를 더한다.
 * Redis는 메시지 전파와 무관하다. 앱이 입력 중 표시·접속 상태 때문에 붙는다.
 */
final class MeasureEnvironment {

    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private MeasureEnvironment() {
    }

    static synchronized void start() {
        if (REDIS.isRunning()) {
            return;
        }
        CdcTestEnvironment.start();
        REDIS.start();
    }

    static String redisHost() {
        return REDIS.getHost();
    }

    static int redisPort() {
        return REDIS.getMappedPort(REDIS_PORT);
    }
}
