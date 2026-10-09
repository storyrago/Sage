package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.events.MessageEvent;

import java.time.Instant;
import java.util.UUID;

/** Message → MessageEvent(Avro 생성 클래스) 변환. 생산자 쪽 매핑은 이 클래스 하나에만 둔다. */
public final class MessageEventRecords {

    private MessageEventRecords() {
    }

    public static MessageEvent toEvent(UUID eventId, MessageEventType type, Instant occurredAt, Message message) {
        return toEvent(eventId, type, occurredAt, message, null);
    }

    /** dereferencedImageUrl: 이 변경으로 참조가 끊긴 이미지. 삭제는 엔티티의 imageUrl을 지운 뒤 기록하므로 따로 받는다. */
    public static MessageEvent toEvent(UUID eventId, MessageEventType type, Instant occurredAt, Message message,
                                       String dereferencedImageUrl) {
        Member author = message.getMember();   // 탈퇴한 회원의 메시지는 작성자가 없다
        return MessageEvent.newBuilder()
                .setEventId(eventId)
                .setEventType(com.example.springboot_realtimechat.events.MessageEventType.valueOf(type.name()))
                .setOccurredAt(occurredAt)
                .setChatroomId(message.getChatRoom().getId())
                .setMessageId(message.getId())
                .setSeq(message.getSeq())
                .setClientMessageId(message.getClientMessageId())
                .setContent(message.getContent())
                .setImageUrl(message.getImageUrl())
                .setAuthorId(author != null ? author.getId() : null)
                .setAuthorNickname(author != null ? author.getNickname() : null)
                .setAuthorProfileImageUrl(author != null ? author.getProfileImageUrl() : null)
                .setReplyToId(message.getReplyTo() != null ? message.getReplyTo().getId() : null)
                .setCreatedAt(message.getCreatedAt())
                .setEditedAt(message.getEditedAt())
                .setDeleted(message.isDeleted())
                .setDereferencedImageUrl(dereferencedImageUrl)
                .build();
    }
}
