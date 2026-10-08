package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.outbox.OutboxEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxWriter;
import com.example.springboot_realtimechat.events.MessageEvent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + OutboxMessageEventRecorderTest.SCOPE
})
@Transactional
class OutboxMessageEventRecorderTest {

    static final String SCOPE = "outbox-recorder-test";
    private static final String CLIENT_ID = "7d3e9a1b-2c4f-4e5a-8b6c-0d1e2f3a4b5c";

    static {
        // 컨텍스트 기동 시 등록 여부를 확인하므로 그 전에 등록해 둔다(배포 단계의 등록을 재현).
        MessageEventTestSupport.register(SCOPE);
    }

    @MockitoBean OutboxWriter outboxWriter;
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member joined(String email, ChatRoom room) {
        Member member = memberService.create(email, "1234", "기록작성자");
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        return member;
    }

    private List<OutboxEvent> appended() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxWriter, atLeast(0)).append(captor.capture());
        return captor.getAllValues();
    }

    private MessageEvent payloadOf(OutboxEvent event) {
        return MessageEventTestSupport.deserialize(SCOPE, event.getPayload());
    }

    @Test
    void 메시지를_저장하면_생성_이벤트를_방_키로_남긴다() {
        ChatRoom room = chatRoomService.create("기록생성방", false, null);
        Member member = joined("recorder-create@e.com", room);

        Message message = messageService.create("안녕", null, member.getId(), room.getId(), null, CLIENT_ID);

        List<OutboxEvent> events = appended();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getAggregateType()).isEqualTo("message");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(room.getId()));
        assertThat(event.getType()).isEqualTo("CREATED");
        MessageEvent record = payloadOf(event);
        assertThat(record.getEventId()).hasToString(event.getId());
        assertThat(record.getMessageId()).isEqualTo(message.getId());
        assertThat(record.getSeq()).isEqualTo(1L);
        assertThat(record.getClientMessageId()).isEqualTo(CLIENT_ID);
        assertThat(record.getContent()).isEqualTo("안녕");
        assertThat(record.getAuthorNickname()).isEqualTo("기록작성자");
    }

    @Test
    void 재전송으로_기존_메시지를_돌려줄_때는_이벤트를_남기지_않는다() {
        ChatRoom room = chatRoomService.create("기록재전송방", false, null);
        Member member = joined("recorder-retry@e.com", room);

        messageService.create("안녕", null, member.getId(), room.getId(), null, CLIENT_ID);
        messageService.create("안녕", null, member.getId(), room.getId(), null, CLIENT_ID);

        assertThat(appended()).extracting(OutboxEvent::getType).containsExactly("CREATED");
    }

    @Test
    void 수정하면_수정된_전체_상태로_수정_이벤트를_남긴다() {
        ChatRoom room = chatRoomService.create("기록수정방", false, null);
        Member member = joined("recorder-update@e.com", room);
        Message message = messageService.create("원본", null, member.getId(), room.getId(), null);

        messageService.update(room.getId(), message.getId(), member.getId(), "수정본");

        List<OutboxEvent> events = appended();
        assertThat(events).extracting(OutboxEvent::getType).containsExactly("CREATED", "UPDATED");
        MessageEvent updated = payloadOf(events.get(1));
        assertThat(updated.getContent()).isEqualTo("수정본");
        assertThat(updated.getEditedAt()).isNotNull();
        assertThat(updated.getSeq()).isEqualTo(1L);
    }

    @Test
    void 삭제하면_삭제_이벤트를_남긴다() {
        ChatRoom room = chatRoomService.create("기록삭제방", false, null);
        Member member = joined("recorder-delete@e.com", room);
        Message message = messageService.create("지울 메시지", null, member.getId(), room.getId(), null);

        messageService.delete(room.getId(), message.getId(), member.getId());

        List<OutboxEvent> events = appended();
        assertThat(events).extracting(OutboxEvent::getType).containsExactly("CREATED", "DELETED");
        MessageEvent deleted = payloadOf(events.get(1));
        assertThat(deleted.getDeleted()).isTrue();
        assertThat(deleted.getContent()).isEmpty();
    }

    @Test
    void 검증에_실패한_요청은_이벤트를_남기지_않는다() {
        ChatRoom room = chatRoomService.create("기록실패방", false, null);
        Member member = joined("recorder-empty@e.com", room);

        assertThatThrownBy(() -> messageService.create("", null, member.getId(), room.getId(), null))
                .isInstanceOf(CustomException.class);

        assertThat(appended()).isEmpty();
    }
}
