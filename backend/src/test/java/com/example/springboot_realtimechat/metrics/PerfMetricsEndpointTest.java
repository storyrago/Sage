package com.example.springboot_realtimechat.metrics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 성능 측정 스택(perf 프로필)에서만 지표를 노출한다. 서비스 포트에는 보이지 않고 관리 포트에서만 인증 없이 보인다.
 * 서버 쪽 p99를 Prometheus에서 계산할 수 있게 HTTP 요청 히스토그램 버킷이 나와야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("perf")
class PerfMetricsEndpointTest {

    private static final int MANAGEMENT_PORT = TestSocketUtils.findAvailableTcpPort();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void managementPort(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT_PORT);
    }

    @Autowired
    Environment environment;

    private HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int servicePort() {
        return Integer.parseInt(environment.getRequiredProperty("local.server.port"));
    }

    @Test
    void 관리_포트에서_인증_없이_prometheus_지표를_노출한다() throws Exception {
        HttpResponse<String> response = get(MANAGEMENT_PORT, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes").contains("application=\"sage\"");
    }

    @Test
    void 서비스_포트에서는_prometheus_지표가_보이지_않는다() throws Exception {
        assertThat(get(servicePort(), "/actuator/prometheus").statusCode()).isNotEqualTo(200);
    }

    @Test
    void HTTP_요청_히스토그램_버킷이_나온다() throws Exception {
        // 서비스 포트로 요청 하나를 보내 http.server.requests를 남긴다(인증 없으므로 401이어도 기록된다).
        get(servicePort(), "/api/chatrooms");

        assertThat(get(MANAGEMENT_PORT, "/actuator/prometheus").body())
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    void 관리_포트에서_readiness를_노출한다() throws Exception {
        assertThat(get(MANAGEMENT_PORT, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }
}
