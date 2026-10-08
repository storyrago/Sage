package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.event.MessageEventRecords;
import com.example.springboot_realtimechat.domain.message.event.MessageEventType;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.events.MessageEvent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

        MessageEvent event = MessageEventRecords.toEvent(eventId, MessageEventType.CREATED, occurredAt, reply);

        assertThat(event.getEventId()).isEqualTo(eventId);
        assertThat(event.getEventType()).isEqualTo(com.example.springboot_realtimechat.events.MessageEventType.CREATED);
        assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(event.getChatroomId()).isEqualTo(room.getId());
        assertThat(event.getMessageId()).isEqualTo(reply.getId());
        assertThat(event.getSeq()).isEqualTo(2L);
        assertThat(event.getClientMessageId()).isEqualTo(CLIENT_ID);
        assertThat(event.getContent()).isEqualTo("답장");
        assertThat(event.getImageUrl()).isNull();
        assertThat(event.getAuthorId()).isEqualTo(author.getId());
        assertThat(event.getAuthorNickname()).isEqualTo("매핑작성자");
        assertThat(event.getReplyToId()).isEqualTo(original.getId());
        assertThat(event.getCreatedAt()).isEqualTo(reply.getCreatedAt());
        assertThat(event.getEditedAt()).isNull();
        assertThat(event.getDeleted()).isFalse();
    }

    @Test
    void 등록된_스키마로_직렬화되고_생성_클래스로_다시_읽힌다() {
        // 생성 클래스의 스키마에는 avro.java.string 속성이 붙는다. 직렬화기가 그것을 떼어 내야 등록된 스키마와 일치한다.
        MessageEventTestSupport.register(SCOPE);
        ChatRoom room = chatRoomService.create("왕복방", false, null);
        Member author = memberService.create("records-roundtrip@e.com", "1234", "왕복작성자");
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message message = messageService.create("지울 메시지", null, author.getId(), room.getId(), null);
        messageService.delete(room.getId(), message.getId(), author.getId());

        MessageEvent event = MessageEventRecords.toEvent(UUID.randomUUID(), MessageEventType.DELETED, Instant.now(), message);
        byte[] payload = MessageEventTestSupport.serialize(SCOPE, event);
        MessageEvent read = MessageEventTestSupport.deserialize(SCOPE, payload);

        // 와이어 포맷 첫 바이트는 매직 바이트 0이다.
        assertThat(payload[0]).isZero();
        assertThat(read.getEventId()).isEqualTo(event.getEventId());
        assertThat(read.getEventType()).isEqualTo(com.example.springboot_realtimechat.events.MessageEventType.DELETED);
        assertThat(read.getContent()).isEmpty();
        assertThat(read.getDeleted()).isTrue();
        assertThat(read.getMessageId()).isEqualTo(message.getId());
        // local-timestamp-micros: 마이크로초까지 보존된다.
        assertThat(read.getCreatedAt()).isEqualTo(message.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(read.getOccurredAt()).isEqualTo(event.getOccurredAt().truncatedTo(ChronoUnit.MILLIS));
    }
}
