package com.example.springboot_realtimechat.presence;

import com.example.springboot_realtimechat.domain.presence.service.PresenceRegistry;

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

    @Mock StringRedisTemplate redis;
    @Mock HashOperations<String, Object, Object> hashOps;
    @InjectMocks PresenceRegistry registry;

    @Test
    void 방_온라인은_해시값_distinct_Long이다() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.values("presence:room:5")).willReturn(List.of("10", "10", "20"));

        assertThat(registry.getRoomOnlineMemberIds(5L)).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void 처음_입장하면_이전방_없음_empty() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn(null);

        assertThat(registry.enterRoom("s1", 5L, "sub-1", 10L)).isEmpty();
        verify(hashOps).put("presence:room:5", "s1", "10");
        verify(hashOps).put("presence:session", "s1", "5|sub-1|10");
    }

    @Test
    void 다른방으로_전환하면_이전방에서_제거하고_이전방id반환() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.enterRoom("s1", 7L, "sub-2", 10L)).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).put("presence:room:7", "s1", "10");
    }

    @Test
    void 현재_채팅subId면_방에서_나가고_방id반환() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.leaveBySubscription("s1", "sub-1")).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).delete("presence:session", "s1");
    }

    @Test
    void 다른_subId_해제는_무시() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.leaveBySubscription("s1", "sub-typing")).isEmpty();
    }

    @Test
    void 접속종료시_현재방에서_제거() {
        given(redis.opsForHash()).willReturn(hashOps);
        given(hashOps.get("presence:session", "s1")).willReturn("5|sub-1|10");

        assertThat(registry.disconnect("s1")).contains(5L);
        verify(hashOps).delete("presence:room:5", "s1");
        verify(hashOps).delete("presence:session", "s1");
    }
}
