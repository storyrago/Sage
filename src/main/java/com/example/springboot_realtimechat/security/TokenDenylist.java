package com.example.springboot_realtimechat.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 발급된 토큰의 무효화 여부를 판단하고 등록한다. 판정은 여기 한 곳에서만 한다.
 * 키는 TTL로 스스로 사라지므로 정리 작업이 없다.
 */
@Slf4j
@Component
public class TokenDenylist {

    private static final String JTI_PREFIX = "jwt:denylist:jti:";
    private static final String MEMBER_PREFIX = "jwt:denylist:member:";

    private final StringRedisTemplate redis;
    private final long accessTokenExpirationMs;

    public TokenDenylist(
            StringRedisTemplate redis,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs
    ) {
        this.redis = redis;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    /**
     * 이 토큰이 무효화됐는지. Redis 조회가 실패하면 통과시킨다(fail-open) —
     * 거부하면 Redis 장애가 전 사용자 로그인 불가로 번진다.
     */
    public boolean isRevoked(String jti, Long memberId, Long issuedAtMillis) {
        try {
            if (jti != null && Boolean.TRUE.equals(redis.hasKey(JTI_PREFIX + jti))) {
                return true;
            }
            if (memberId == null || issuedAtMillis == null) {
                return false;
            }
            String revokedAt = redis.opsForValue().get(MEMBER_PREFIX + memberId);
            return revokedAt != null && issuedAtMillis < Long.parseLong(revokedAt);
        } catch (Exception e) {
            log.warn("거부목록 조회 실패 — 토큰을 통과시킨다: memberId={}", memberId, e);
            return false;
        }
    }

    /** 토큰 하나를 무효화한다. TTL은 그 토큰의 남은 수명. */
    public void revokeToken(String jti, Long expiresAtMillis) {
        long remainingMs = expiresAtMillis - System.currentTimeMillis();
        if (remainingMs <= 0) {
            return; // 이미 만료된 토큰은 서명 검증에서 걸린다
        }
        redis.opsForValue().set(JTI_PREFIX + jti, "1", Duration.ofMillis(remainingMs));
    }

    /**
     * 이 시각 이전에 발급된 그 회원의 토큰을 전부 무효화한다.
     * 플래그가 아니라 시각이라, 재로그인으로 받은 토큰은 통과한다.
     */
    public void revokeMember(Long memberId) {
        redis.opsForValue().set(
                MEMBER_PREFIX + memberId,
                String.valueOf(System.currentTimeMillis()),
                Duration.ofMillis(accessTokenExpirationMs));
    }

    /**
     * 회원 단위 무효화를 해제한다. 재로그인이 같은 초에 일어나도 새 토큰이 막히지 않게 한다.
     * 부작용: 이 회원에게 걸려 있던 회원 단위 무효화를 통째로 해제한다 — 다른 기기의 구 토큰도 함께 되살아난다.
     */
    public void clearMember(Long memberId) {
        try {
            redis.delete(MEMBER_PREFIX + memberId);
        } catch (Exception e) {
            log.warn("회원 단위 무효화 해제 실패: memberId={}", memberId, e);
        }
    }
}
