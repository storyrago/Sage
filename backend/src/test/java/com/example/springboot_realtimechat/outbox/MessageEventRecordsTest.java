package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.event.MessageEventRecords;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.event.MessageEventType;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MessageEventRecordsTest {

    private static final String SCOPE = "records-test";
    private static final String CLIENT_ID = "0b6f2c4e-8a1d-4f3b-9c7e-5d2a1b0c9e8f";

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @Test
    void 이벤트에_메시지_전체_상태를_싣는다() {
        ChatRoom room = chatRoomService.create("매핑방", false, null);
        Member author = memberService.create("records-map@e.com", "1234", "매핑작성자");
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message original = messageService.create("원문", null, author.getId(), room.getId(), null);
        Message reply = messageService.create("답장", null, author.getId(), room.getId(), original.getId(), CLIENT_ID);
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-08T01:02:03.456Z");

        GenericRecord record = MessageEventRecords.toRecord(eventId, MessageEventType.CREATED, occurredAt, reply);

        assertThat(record.get("eventId")).hasToString(eventId.toString());
        assertThat(record.get("eventType")).hasToString("CREATED");
        assertThat(record.get("occurredAt")).isEqualTo(occurredAt.toEpochMilli());
        assertThat(record.get("chatroomId")).isEqualTo(room.getId());
        assertThat(record.get("messageId")).isEqualTo(reply.getId());
        assertThat(record.get("seq")).isEqualTo(2L);
        assertThat(record.get("clientMessageId")).hasToString(CLIENT_ID);
        assertThat(record.get("content")).hasToString("답장");
        assertThat(record.get("imageUrl")).isNull();
        assertThat(record.get("authorId")).isEqualTo(author.getId());
        assertThat(record.get("authorNickname")).hasToString("매핑작성자");
        assertThat(record.get("replyToId")).isEqualTo(original.getId());
        assertThat(record.get("createdAt")).isEqualTo(MessageEventRecords.toLocalMicros(reply.getCreatedAt()));
        assertThat(record.get("editedAt")).isNull();
        assertThat(record.get("deleted")).isEqualTo(false);
    }

    @Test
    void 삭제된_메시지는_빈_본문과_삭제_표시로_직렬화되고_다시_읽힌다() {
        MessageEventTestSupport.register(SCOPE);
        ChatRoom room = chatRoomService.create("왕복방", false, null);
        Member author = memberService.create("records-roundtrip@e.com", "1234", "왕복작성자");
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message message = messageService.create("지울 메시지", null, author.getId(), room.getId(), null);
        messageService.delete(room.getId(), message.getId(), author.getId());

        GenericRecord record = MessageEventRecords.toRecord(UUID.randomUUID(), MessageEventType.DELETED, Instant.now(), message);
        byte[] payload;
        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(MockSchemaRegistry.getClientForScope(SCOPE), Map.of(
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://" + SCOPE,
                AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, false,
                AbstractKafkaSchemaSerDeConfig.NORMALIZE_SCHEMAS, true))) {
            payload = serializer.serialize(MessageEventSchema.TOPIC, record);
        }
        GenericRecord read = MessageEventTestSupport.deserialize(SCOPE, payload);

        // 와이어 포맷 첫 바이트는 매직 바이트 0이다.
        assertThat(payload[0]).isZero();
        assertThat(read.get("eventType")).hasToString("DELETED");
        assertThat(read.get("content")).hasToString("");
        assertThat(read.get("deleted")).isEqualTo(true);
        assertThat(read.get("messageId")).isEqualTo(message.getId());
    }

    @Test
    void 시간대_없는_시각은_UTC로_가정한_epoch_마이크로초다() {
        // Avro local-timestamp-micros 명세: 시간대 없는 날짜·시각을 UTC 기준 epoch로 센다.
        assertThat(MessageEventRecords.toLocalMicros(LocalDateTime.of(1970, 1, 1, 0, 0, 1, 2_500)))
                .isEqualTo(1_000_002L);
    }
}
