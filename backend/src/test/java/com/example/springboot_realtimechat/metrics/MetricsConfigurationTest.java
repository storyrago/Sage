package com.example.springboot_realtimechat.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// OTLP_METRICS_ENABLED 등 환경변수가 없는 로컬·CI·테스트 환경에서 익스포트가 실제로
// 꺼져 있는지, WebSocket 동시 접속자 게이지는 정상 등록되는지 검증한다.
@SpringBootTest
class MetricsConfigurationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void 환경변수가_없으면_OTLP_레지스트리가_등록되지_않는다() {
        // enabled 프로퍼티가 false면 OtlpMetricsExportAutoConfiguration이 OtlpMeterRegistry 빈을
        // 아예 만들지 않는다 — 빈 존재 여부로 "꺼져 있다"를 직접 검증한다.
        assertThat(context.getBeansOfType(OtlpMeterRegistry.class)).isEmpty();
    }

    @Test
    void WebSocket_동시_접속자_게이지가_등록된다() {
        Gauge gauge = meterRegistry.get("sage.websocket.users").gauge();

        assertThat(gauge.value()).isZero();
    }
}
