package com.example.springboot_realtimechat.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PresenceRegistry {

    private static final String KEY = "presence:sessions";

    private final StringRedisTemplate redis;

    public void connect(String sessionId, Long memberId) {
        redis.opsForHash().put(KEY, sessionId, String.valueOf(memberId));
    }

    public Optional<Long> disconnect(String sessionId) {
        Object previous = redis.opsForHash().get(KEY, sessionId);
        redis.opsForHash().delete(KEY, sessionId);
        return previous == null ? Optional.empty() : Optional.of(Long.valueOf(previous.toString()));
    }

    public Set<Long> getOnlineMemberIds() {
        return redis.opsForHash().values(KEY).stream()
                .map(value -> Long.valueOf(value.toString()))
                .collect(Collectors.toSet());
    }
}
