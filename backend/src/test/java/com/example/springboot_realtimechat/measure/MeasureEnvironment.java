package com.example.springboot_realtimechat.measure;

import com.example.springboot_realtimechat.outbox.CdcTestEnvironment;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

/**
 * 측정 테스트 공용 환경. CDC 컨테이너 4종 위에 Redis와, 앱↔Redis 사이에 끼우는 Toxiproxy를 더한다.
 * 앱은 Redis에 직접 붙지 않고 Toxiproxy를 거친다 — 그래야 연결을 끊었다 이을 수 있다.
 */
final class MeasureEnvironment {

    private static final Network NETWORK = Network.newNetwork();
    // Toxiproxy 컨테이너가 기본으로 여는 프록시 포트 범위(8666~8697)의 첫 번째
    private static final int PROXY_PORT = 8666;

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    private static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
                    .withNetwork(NETWORK);

    private static Proxy redisProxy;

    private MeasureEnvironment() {
    }

    static synchronized void start() {
        if (redisProxy != null) {
            return;
        }
        CdcTestEnvironment.start();
        Startables.deepStart(REDIS, TOXIPROXY).join();
        try {
            ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            redisProxy = client.createProxy("redis", "0.0.0.0:" + PROXY_PORT, "redis:6379");
        } catch (IOException e) {
            throw new IllegalStateException("Toxiproxy 프록시 생성 실패", e);
        }
    }

    static String redisHost() {
        return TOXIPROXY.getHost();
    }

    static int redisPort() {
        return TOXIPROXY.getMappedPort(PROXY_PORT);
    }

    /** 프록시를 끈다. 열린 연결은 끊기고 새 연결은 거부된다. */
    static void cutRedis() throws IOException {
        redisProxy.disable();
    }

    static void restoreRedis() throws IOException {
        redisProxy.enable();
    }
}
