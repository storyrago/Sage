package com.example.springboot_realtimechat.presence;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PresenceRegistry {

    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();

    public void connect(String sessionId, Long memberId) {
        sessions.put(sessionId, memberId);
    }

    public Optional<Long> disconnect(String sessionId) {
        return Optional.ofNullable(sessions.remove(sessionId));
    }

    public Set<Long> getOnlineMemberIds() {
        return new HashSet<>(sessions.values());
    }
}
