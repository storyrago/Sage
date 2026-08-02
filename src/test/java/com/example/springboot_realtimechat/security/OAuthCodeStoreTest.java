package com.example.springboot_realtimechat.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Redis를 쓴다. TTL과 1회용 소비가 이 기능의 핵심이라 mock으로는 검증되지 않는다.
@SpringBootTest
class OAuthCodeStoreTest {

    @Autowired OAuthCodeStore store;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 424242L;

    private String issuedCode;

    @AfterEach
    void cleanup() {
        if (issuedCode != null) {
            redis.delete("oauth:code:" + issuedCode);
            issuedCode = null;
        }
    }

    @Test
    void 발급한_코드로_회원_id를_얻는다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(store.consume(issuedCode)).isEqualTo(MEMBER_ID);
    }

    @Test
    void 같은_코드는_두_번_쓸_수_없다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(store.consume(issuedCode)).isEqualTo(MEMBER_ID);
        assertThat(store.consume(issuedCode)).isNull();
    }

    @Test
    void 소비하면_키가_남지_않는다() {
        issuedCode = store.issue(MEMBER_ID);

        store.consume(issuedCode);

        assertThat(redis.hasKey("oauth:code:" + issuedCode)).isFalse();
    }

    @Test
    void 존재하지_않는_코드는_null이다() {
        assertThat(store.consume("no-such-code")).isNull();
    }

    @Test
    void 비어_있는_코드는_null이다() {
        assertThat(store.consume(null)).isNull();
        assertThat(store.consume("")).isNull();
        assertThat(store.consume("   ")).isNull();
    }

    @Test
    void 코드의_TTL은_60초다() {
        issuedCode = store.issue(MEMBER_ID);

        Long ttl = redis.getExpire("oauth:code:" + issuedCode);
        assertThat(ttl).isBetween(50L, 60L);
    }

    @Test
    void 두_번_발급하면_서로_다른_코드다() {
        String first = store.issue(MEMBER_ID);
        String second = store.issue(MEMBER_ID);
        redis.delete("oauth:code:" + first);
        redis.delete("oauth:code:" + second);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 코드는_추측할_수_없을_만큼_길다() {
        issuedCode = store.issue(MEMBER_ID);

        assertThat(issuedCode).hasSizeGreaterThanOrEqualTo(32);
    }
}
