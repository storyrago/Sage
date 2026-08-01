package com.example.springboot_realtimechat.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 실제 Redis를 쓴다. TTL과 키 수명이 이 기능의 핵심이라 mock으로는 검증되지 않는다.
@SpringBootTest
class TokenDenylistTest {

    @Autowired TokenDenylist denylist;
    @Autowired JwtTokenProvider jwtTokenProvider;
    @Autowired StringRedisTemplate redis;

    private static final Long MEMBER_ID = 987654L;
    private static final String JTI = "test-jti-0001";
    private static final String JTI_KEY = "jwt:denylist:jti:" + JTI;
    private static final String MEMBER_KEY = "jwt:denylist:member:" + MEMBER_ID;

    @AfterEach
    void cleanup() {
        redis.delete(JTI_KEY);
        redis.delete(MEMBER_KEY);
    }

    @Test
    void 아무것도_무효화하지_않으면_통과한다() {
        assertThat(denylist.isRevoked(JTI, MEMBER_ID, System.currentTimeMillis())).isFalse();
    }

    @Test
    void 무효화된_jti는_거부한다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        assertThat(denylist.isRevoked(JTI, MEMBER_ID, System.currentTimeMillis())).isTrue();
    }

    @Test
    void 같은_회원의_다른_jti는_통과한다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        assertThat(denylist.isRevoked("other-jti", MEMBER_ID, System.currentTimeMillis())).isFalse();
    }

    @Test
    void jti_키의_TTL이_토큰_만료까지로_설정된다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() + 60_000L);

        Long ttl = redis.getExpire(JTI_KEY);
        assertThat(ttl).isBetween(1L, 60L);
    }

    @Test
    void 이미_만료된_토큰은_거부목록에_올리지_않는다() {
        denylist.revokeToken(JTI, System.currentTimeMillis() - 1L);

        assertThat(redis.hasKey(JTI_KEY)).isFalse();
    }

    @Test
    void 회원_단위_무효화는_그_이전에_발급된_토큰을_거부한다() {
        long issuedAt = System.currentTimeMillis() - 5_000L;

        denylist.revokeMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAt)).isTrue();
    }

    @Test
    void 회원_단위_무효화_이후에_발급된_토큰은_통과한다() {
        denylist.revokeMember(MEMBER_ID);
        long issuedAfter = System.currentTimeMillis() + 5_000L;

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAfter)).isFalse();
    }

    @Test
    void 회원_단위_키를_지우면_옛_토큰도_다시_통과한다() {
        long issuedAt = System.currentTimeMillis() - 5_000L;
        denylist.revokeMember(MEMBER_ID);

        denylist.clearMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, issuedAt)).isFalse();
    }

    @Test
    void 회원_단위_키의_TTL이_액세스_토큰_수명으로_설정된다() {
        denylist.revokeMember(MEMBER_ID);

        Long ttl = redis.getExpire(MEMBER_KEY);
        // 테스트 설정의 access-token-expiration-ms는 3600000
        assertThat(ttl).isBetween(3500L, 3600L);
    }

    @Test
    void 발급된_토큰에_jti가_실린다() {
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        assertThat(jwtTokenProvider.getJti(token)).isNotBlank();
    }

    @Test
    void 같은_회원의_두_토큰은_서로_다른_jti를_가진다() {
        String first = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");
        String second = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        assertThat(jwtTokenProvider.getJti(first)).isNotEqualTo(jwtTokenProvider.getJti(second));
    }

    @Test
    void 발급_시각을_토큰에서_읽는다() {
        long before = System.currentTimeMillis() - 1_000L;
        String token = jwtTokenProvider.createAccessToken(MEMBER_ID, "denylist@e.com");

        Long issuedAt = jwtTokenProvider.getIssuedAt(token);

        assertThat(issuedAt).isNotNull();
        assertThat(issuedAt).isGreaterThanOrEqualTo(before);
    }

    @Test
    void 잘못된_토큰의_jti와_발급_시각은_null() {
        assertThat(jwtTokenProvider.getJti("not-a-token")).isNull();
        assertThat(jwtTokenProvider.getIssuedAt("not-a-token")).isNull();
    }

    @Test
    void 발급_시각을_모르면_회원_단위_판정을_하지_않는다() {
        denylist.revokeMember(MEMBER_ID);

        assertThat(denylist.isRevoked(null, MEMBER_ID, null)).isFalse();
    }

    // 실제 Redis 대신 mock으로 조회 실패를 흉내낸다 — 위 테스트들의 @Autowired denylist는
    // 실제 Redis를 쓰므로 장애를 재현할 수 없다.
    @Test
    void redis_조회가_실패하면_통과시키고_경고_로그를_남긴다() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        when(brokenRedis.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        TokenDenylist denylistWithBrokenRedis = new TokenDenylist(brokenRedis, 3_600_000L);

        Logger logger = (Logger) LoggerFactory.getLogger(TokenDenylist.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            boolean revoked = denylistWithBrokenRedis.isRevoked(JTI, MEMBER_ID, System.currentTimeMillis());

            assertThat(revoked).isFalse();
            assertThat(appender.list).anyMatch(event ->
                    event.getLevel() == Level.WARN && event.getFormattedMessage().contains("거부목록 조회 실패"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
