package com.example.springboot_realtimechat.presence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PresenceRegistryTest {

    private static final String KEY = "presence:sessions";

    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashOps;
    @InjectMocks PresenceRegistry registry;

    @Test
    void 접속하면_해시에_세션과_멤버를_저장한다() {
        given(redis.opsForHash()).willReturn(hashOps);

        registry.connect("session-1", 10L);

        verify(hashOps).put(KEY, "session-1", "10");
    }

    @Test
    void 온라인_목록은_해시_값들의_distinct_Long_집합이다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.values(KEY)).willReturn(List.of("10", "10", "20"));

        assertThat(registry.getOnlineMemberIds()).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 끊기면_해당_세션을_삭제하고_memberId를_반환한다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get(KEY, "session-1")).willReturn("10");

        assertThat(registry.disconnect("session-1")).contains(10L);
        verify(hashOps).delete(KEY, "session-1");
    }

    @Test
    void 모르는_세션을_끊으면_empty를_반환한다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get(KEY, "nope")).willReturn(null);

        assertThat(registry.disconnect("nope")).isEmpty();
    }
}
