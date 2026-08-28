package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoomMember;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
public class UnreadCountTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired EntityManager entityManager;

    @Test
    void 가입시_lastRead가_방_최신메시지id로_세팅() {
        Member owner = memberService.create("o@e.com", "1234", "owner");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(owner.getId(), room.getId(), null);
        messageService.create("m1", null, owner.getId(), room.getId(), null);
        var last = messageService.create("m2", null, owner.getId(), room.getId(), null);

        Member joiner = memberService.create("j@e.com", "1234", "joiner");
        ChatRoomMember cm = chatRoomMemberService.join(joiner.getId(), room.getId(), null);

        assertThat(cm.getLastReadMessageId()).isEqualTo(last.getId());
    }

    @Test
    void 안읽음_카운트는_내메시지_삭제_제외하고_lastRead_이후만() {
        Member a = memberService.create("a@e.com", "1234", "a");
        Member b = memberService.create("b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);   // a: lastRead=null(빈 방)
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        // b가 5개 보냄. a 입장에서 5개 안읽음이어야(내것 아님, 삭제 아님)
        for (int i = 0; i < 5; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);
        // a가 1개 보냄 → a의 안읽음엔 안 셈(내 메시지)
        messageService.create("mine", null, a.getId(), room.getId(), null);
        // b의 1개 삭제 → 안읽음에서 빠짐
        var del = messageService.create("del", null, b.getId(), room.getId(), null);
        messageService.delete(room.getId(), del.getId(), b.getId());

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(5L);  // b0~b4
    }

    @Test
    void 안읽음은_lastRead_이후만_세고_0인방도_결과에_포함() {
        Member a = memberService.create("za@e.com", "1234", "za");
        Member b = memberService.create("zb@e.com", "1234", "zb");

        // room1: b joins empty, sends 2 "old", THEN a joins (a.lastRead = old2.id, non-null),
        // then b sends 2 "new" → a's unread in room1 must be exactly 2 (id > lastRead branch).
        ChatRoom room1 = chatRoomService.create("r1", false, null);
        chatRoomMemberService.join(b.getId(), room1.getId(), null);
        messageService.create("old1", null, b.getId(), room1.getId(), null);
        messageService.create("old2", null, b.getId(), room1.getId(), null);
        chatRoomMemberService.join(a.getId(), room1.getId(), null);            // a.lastRead = old2.id
        messageService.create("new1", null, b.getId(), room1.getId(), null);
        messageService.create("new2", null, b.getId(), room1.getId(), null);

        // room2: a message exists, then a joins (a.lastRead = that msg id), no newer → unread 0,
        // but room2 must still appear in the result with count 0 (LEFT JOIN / COUNT(m)=0, not 1).
        ChatRoom room2 = chatRoomService.create("r2", false, null);
        chatRoomMemberService.join(b.getId(), room2.getId(), null);
        messageService.create("r2m1", null, b.getId(), room2.getId(), null);
        chatRoomMemberService.join(a.getId(), room2.getId(), null);           // a.lastRead = r2m1.id

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var c1 = counts.stream().filter(c -> c.getChatroomId().equals(room1.getId())).findFirst().orElseThrow();
        assertThat(c1.getUnreadCount()).isEqualTo(2L);   // only new1,new2 (id > lastRead)
        var c2 = counts.stream().filter(c -> c.getChatroomId().equals(room2.getId())).findFirst().orElseThrow();
        assertThat(c2.getUnreadCount()).isEqualTo(0L);   // 0-unread room present with count 0
    }

    @Test
    void 읽음처리하면_안읽음_0() {
        Member a = memberService.create("ra@e.com", "1234", "ra");
        Member b = memberService.create("rb@e.com", "1234", "rb");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);
        for (int i = 0; i < 3; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);

        chatRoomMemberService.markRead(a.getId(), room.getId());

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(0L);
    }

    @Test
    void 미참여자_markRead는_NOT_JOINED_ROOM() {
        Member a = memberService.create("mja@e.com", "1234", "mja");
        Member outsider = memberService.create("outsider2@e.com", "1234", "outsider2");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);

        assertThatThrownBy(() -> chatRoomMemberService.markRead(outsider.getId(), room.getId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 작성자가_없는_메시지도_안읽음_집계에_포함된다() {
        Member a = memberService.create("una@e.com", "1234", "una");
        Member b = memberService.create("unb@e.com", "1234", "unb");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        var anon = messageService.create("탈퇴자 메시지", null, b.getId(), room.getId(), null);
        // 탈퇴 시 작성자 참조만 끊는 것과 동일한 상태를 만든다(AnonymousAuthorTest와 같은 방식).
        entityManager.createQuery("UPDATE Message m SET m.member = null WHERE m.id = :id")
                .setParameter("id", anon.getId())
                .executeUpdate();
        entityManager.clear();

        var counts = chatRoomMemberService.getUnreadCounts(a.getId());
        var forRoom = counts.stream().filter(c -> c.getChatroomId().equals(room.getId())).findFirst().orElseThrow();
        assertThat(forRoom.getUnreadCount()).isEqualTo(1L);
    }

    @Test
    void 방_멤버_조회_email_접근가능() {
        Member a = memberService.create("ma@e.com", "1234", "ma");
        Member b = memberService.create("mb@e.com", "1234", "mb");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        var members = chatRoomMemberRepository.findMembersByChatRoomId(room.getId());
        assertThat(members).extracting(Member::getEmail)
                .containsExactlyInAnyOrder("ma@e.com", "mb@e.com");
    }
}
