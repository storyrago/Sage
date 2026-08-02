package com.example.springboot_realtimechat.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 소셜 로그인 리다이렉트가 실어 나르는 일회용 코드. 토큰을 URL에 싣지 않기 위한 것이다.
 * 코드는 짧게 살고 한 번만 쓸 수 있으므로, 로그에 남아도 의미가 없다.
 */
@Component
@RequiredArgsConstructor
public class OAuthCodeStore {

    private static final String PREFIX = "oauth:code:";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    /** 코드를 만들어 회원 id와 함께 저장한다. 저장에 실패하면 로그인이 성립하지 않으므로 예외를 삼키지 않는다. */
    public String issue(Long memberId) {
        String code = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIX + code, String.valueOf(memberId), TTL);
        return code;
    }

    /**
     * 코드를 소비하고 회원 id를 돌려준다. 없거나 이미 쓴 코드면 null.
     * 조회와 삭제가 한 명령(GETDEL)이라, 같은 코드로 동시에 들어온 교환이 둘 다 성공할 수 없다.
     */
    public Long consume(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String memberId = redis.opsForValue().getAndDelete(PREFIX + code);
        return memberId != null ? Long.valueOf(memberId) : null;
    }
}
