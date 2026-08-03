package com.example.springboot_realtimechat.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

// 실시간 채팅이 본체인 앱에서 동시 접속자 수는 기본 메트릭(JVM·HTTP·DB)에 없는 핵심 지표다.
// SimpUserRegistry는 @EnableWebSocketMessageBroker가 등록하는 빈으로, 로그인된(사용자 식별이 붙은)
// WebSocket 세션의 고유 사용자 수를 그대로 들고 있어 별도 카운터 없이 게이지로 노출한다.
@Component
@RequiredArgsConstructor
public class WebSocketMetrics implements MeterBinder {

    private final SimpUserRegistry userRegistry;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("sage.websocket.users", userRegistry, SimpUserRegistry::getUserCount)
                .description("현재 WebSocket으로 연결된 고유 사용자 수")
                .baseUnit("users")
                .register(registry);
    }
}
