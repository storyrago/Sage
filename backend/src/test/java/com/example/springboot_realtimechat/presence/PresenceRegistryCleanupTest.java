package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.domain.presence.service.PresenceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// SCAN 기반 정리가 실제로 presence:*만 골라내는지는 mock으로는 검증되지 않는다.
@SpringBootTest
class PresenceRegistryCleanupTest {

    @Autowired PresenceRegistry presenceRegistry;
    @Autowired StringRedisTemplate redis;

    private static final String PRESENCE_SESSION_KEY = "presence:session";
    private static final String PRESENCE_ROOM_KEY = "presence:room:5";
    private static final String DENYLIST_KEY = "jwt:denylist:jti:cleanup-test";
    private static final String LOGIN_FAIL_KEY = "login:fail:203.0.113.99";

    @AfterEach
    void cleanup() {
        redis.delete(PRESENCE_SESSION_KEY);
        redis.delete(PRESENCE_ROOM_KEY);
        redis.delete(DENYLIST_KEY);
        redis.delete(LOGIN_FAIL_KEY);
    }

    @Test
    void 기동_정리는_presence_항목만_지우고_거부목록과_로그인_실패_카운터는_남긴다() {
        redis.opsForHash().put(PRESENCE_SESSION_KEY, "s1", "5|sub-1|10");
        redis.opsForHash().put(PRESENCE_ROOM_KEY, "s1", "10");
        redis.opsForValue().set(DENYLIST_KEY, "1");
        redis.opsForValue().set(LOGIN_FAIL_KEY, "3");

        presenceRegistry.cleanupStaleEntries();

        assertThat(redis.hasKey(PRESENCE_SESSION_KEY)).isFalse();
        assertThat(redis.hasKey(PRESENCE_ROOM_KEY)).isFalse();
        assertThat(redis.hasKey(DENYLIST_KEY)).isTrue();
        assertThat(redis.hasKey(LOGIN_FAIL_KEY)).isTrue();
    }

    @Test
    void presence_항목이_없어도_예외_없이_끝난다() {
        presenceRegistry.cleanupStaleEntries();
    }
}
