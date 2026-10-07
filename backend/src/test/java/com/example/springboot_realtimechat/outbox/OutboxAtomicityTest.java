package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.outbox.OutboxWriter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// 클래스 레벨 @Transactional을 걸지 않는다 — 서비스 트랜잭션이 실제로 롤백됐는지 새 조회로 확인해야 한다.
@SpringBootTest(properties = {
        "app.outbox.enabled=true",
        "app.outbox.schema-registry-url=mock://" + OutboxAtomicityTest.SCOPE
})
class OutboxAtomicityTest {

    static final String SCOPE = "outbox-atomicity-test";

    static {
        MessageEventTestSupport.register(SCOPE);
    }

    @MockitoBean OutboxWriter outboxWriter;
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired MemberRepository memberRepository;

    private Member author;
    private Long roomId;

    @Test
    void 이벤트_기록이_실패하면_메시지도_저장되지_않는다() {
        author = memberService.create("outbox-atomicity@e.com", "1234", "원자성");
        roomId = chatRoomService.create("원자성방", false, null).getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
        doThrow(new IllegalStateException("outbox 기록 실패")).when(outboxWriter).append(any());

        assertThatThrownBy(() -> messageService.create("남으면 안 되는 메시지", null, author.getId(), roomId, null))
                .isInstanceOf(IllegalStateException.class);

        ChatRoom room = chatRoomRepository.findById(roomId).orElseThrow();
        assertThat(messageRepository.findLatestByChatRoom(room, PageRequest.of(0, 10))).isEmpty();
        // 순번 발급도 함께 되돌아가 다음 메시지가 1번을 받는다.
        assertThat(room.getLastMessageSeq()).isZero();
    }

    @AfterEach
    void tearDown() {
        if (roomId != null) {
            chatRoomRepository.findById(roomId).ifPresent(room -> {
                messageRepository.findLatestByChatRoom(room, PageRequest.of(0, 50)).forEach(messageRepository::delete);
                chatRoomMemberRepository.findByChatRoom(room).forEach(chatRoomMemberRepository::delete);
                chatRoomRepository.delete(room);
            });
        }
        if (author != null) {
            memberRepository.delete(author);
        }
    }
}
