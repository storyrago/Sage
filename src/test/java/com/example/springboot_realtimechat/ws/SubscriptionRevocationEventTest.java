package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.event.RoomLeftEvent;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.event.ImageCleanupListener;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스가 실제로 회수 이벤트를 발행하는지를 고정한다. AFTER_COMMIT 리스너 실행 자체는
// SubscriptionRevocationIntegrationTest·ImageCleanupListener 선례로 이미 검증돼 있어
// 여기서는 발행 여부만 본다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class SubscriptionRevocationEventTest {

    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ApplicationEvents events;

    @Test
    void 방을_나가면_RoomLeftEvent가_발행된다() {
        Member member = memberService.create("leave-event@e.com", "1234", "leave");
        ChatRoom room = chatRoomService.create("이벤트방", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);

        chatRoomMemberService.leave(member.getId(), room.getId());

        assertThat(events.stream(RoomLeftEvent.class).toList())
                .containsExactly(new RoomLeftEvent(member.getId(), room.getId(), ErrorCode.ROOM_MEMBERSHIP_REVOKED));
    }

    @Test
    void 회원이_탈퇴하면_MemberDeletedEvent가_발행된다() {
        Member member = memberService.create("delete-event@e.com", "1234", "delete");

        memberService.delete(member.getId());

        assertThat(events.stream(MemberDeletedEvent.class).toList())
                .containsExactly(new MemberDeletedEvent(member.getId()));
    }
}
