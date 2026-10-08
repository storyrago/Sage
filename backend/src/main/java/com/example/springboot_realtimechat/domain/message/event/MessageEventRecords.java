package com.example.springboot_realtimechat.domain.message.event;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.entity.Message;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Message → MessageEvent(Avro) 변환. 생산자 쪽 매핑은 이 클래스 하나에만 둔다. */
public final class MessageEventRecords {

    private MessageEventRecords() {
    }

    public static GenericRecord toRecord(UUID eventId, MessageEventType type, Instant occurredAt, Message message) {
        Schema schema = MessageEventSchema.SCHEMA;
        Member author = message.getMember();   // 탈퇴한 회원의 메시지는 작성자가 없다
        return new GenericRecordBuilder(schema)
                .set("eventId", eventId.toString())
                .set("eventType", new GenericData.EnumSymbol(schema.getField("eventType").schema(), type.name()))
                .set("occurredAt", occurredAt.toEpochMilli())
                .set("chatroomId", message.getChatRoom().getId())
                .set("messageId", message.getId())
                .set("seq", message.getSeq())
                .set("clientMessageId", message.getClientMessageId())
                .set("content", message.getContent())
                .set("imageUrl", message.getImageUrl())
                .set("authorId", author != null ? author.getId() : null)
                .set("authorNickname", author != null ? author.getNickname() : null)
                .set("authorProfileImageUrl", author != null ? author.getProfileImageUrl() : null)
                .set("replyToId", message.getReplyTo() != null ? message.getReplyTo().getId() : null)
                .set("createdAt", toLocalMicros(message.getCreatedAt()))
                .set("editedAt", message.getEditedAt() != null ? toLocalMicros(message.getEditedAt()) : null)
                .set("deleted", message.isDeleted())
                .build();
    }

    /** Avro local-timestamp-micros: 시간대 없는 날짜·시각을 UTC로 가정한 epoch 마이크로초. DB DATETIME(6)과 같은 정밀도다. */
    public static long toLocalMicros(LocalDateTime time) {
        return time.toEpochSecond(ZoneOffset.UTC) * 1_000_000L + time.getNano() / 1_000L;
    }
}
