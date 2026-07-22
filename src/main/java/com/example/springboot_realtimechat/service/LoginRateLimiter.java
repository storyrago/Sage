package com.example.springboot_realtimechat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

// IP당 로그인 실패 횟수를 Redis 고정 창(fixed window)으로 센다. 임계치 초과 시 차단.
// Redis 장애 시엔 fail-open(로그인을 막지 않음) — 가용성 우선.
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 10;
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final String PREFIX = "login:fail:";

    private final StringRedisTemplate redis;

    private String key(String ip) {
        return PREFIX + ip;
    }

    public boolean isBlocked(String ip) {
        try {
            String value = redis.opsForValue().get(key(ip));
            return value != null && Integer.parseInt(value) >= MAX_FAILURES;
        } catch (Exception e) {
            return false; // fail-open
        }
    }

    public void recordFailure(String ip) {
        try {
            Long count = redis.opsForValue().increment(key(ip));
            if (count != null && count == 1L) {
                redis.expire(key(ip), WINDOW); // 첫 실패에만 TTL 설정
            }
        } catch (Exception e) {
            // fail-open: 무시
        }
    }

    public void reset(String ip) {
        try {
            redis.delete(key(ip));
        } catch (Exception e) {
            // 무시
        }
    }
}
