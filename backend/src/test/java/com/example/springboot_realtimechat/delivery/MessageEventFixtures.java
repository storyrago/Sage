package com.example.springboot_realtimechat.delivery;

import com.example.springboot_realtimechat.events.MessageEvent;
import com.example.springboot_realtimechat.events.MessageEventType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public final class MessageEventFixtures {

    private MessageEventFixtures() {
    }

    public static MessageEvent event(MessageEventType type, long roomId, long messageId, long seq,
                                     Long authorId, Long replyToId, boolean deleted) {
        return MessageEvent.newBuilder()
                .setEventId(UUID.randomUUID())
                .setEventType(type)
                .setOccurredAt(Instant.parse("2026-10-08T00:00:00Z"))
                .setChatroomId(roomId)
                .setMessageId(messageId)
                .setSeq(seq)
                .setClientMessageId(null)
                .setContent(deleted ? "" : "본문")
                .setImageUrl(null)
                .setAuthorId(authorId)
                .setAuthorNickname(authorId == null ? null : "작성자")
                .setAuthorProfileImageUrl(null)
                .setReplyToId(replyToId)
                .setCreatedAt(LocalDateTime.of(2026, 10, 8, 9, 0, 0, 123_456_000))
                .setEditedAt(null)
                .setDeleted(deleted)
                .build();
    }
}
