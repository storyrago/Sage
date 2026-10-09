package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.dto.UnreadCountResponse;
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

    private UnreadCountResponse countsFor(Long memberId, Long roomId) {
        return chatRoomMemberService.getUnreadCounts(memberId).stream()
                .filter(c -> c.getChatroomId().equals(roomId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 가입시_읽은_위치가_방_최신_순번과_그_메시지id로_세팅() {
        Member owner = memberService.create("o@e.com", "1234", "owner");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(owner.getId(), room.getId(), null);
        messageService.create("m1", null, owner.getId(), room.getId(), null);
        var last = messageService.create("m2", null, owner.getId(), room.getId(), null);

        Member joiner = memberService.create("j@e.com", "1234", "joiner");
        ChatRoomMember cm = chatRoomMemberService.join(joiner.getId(), room.getId(), null);

        assertThat(cm.getLastReadSeq()).isEqualTo(2L);
        assertThat(cm.getLastReadMessageId()).isEqualTo(last.getId());
    }

    @Test
    void 안읽음은_최신순번에서_읽은순번을_뺀_값이고_삭제도_포함한다() {
        Member a = memberService.create("a@e.com", "1234", "a");
        Member b = memberService.create("b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        for (int i = 0; i < 5; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);
        var counts = countsFor(a.getId(), room.getId());
        assertThat(counts.getUnreadCount()).isEqualTo(5L);
        assertThat(counts.getLastMessageSeq()).isEqualTo(5L);
        assertThat(counts.getLastReadSeq()).isZero();

        // 삭제된 메시지도 순번을 차지하므로 센다("삭제된 메시지"도 한 건).
        var del = messageService.create("del", null, b.getId(), room.getId(), null);
        messageService.delete(room.getId(), del.getId(), b.getId());
        assertThat(countsFor(a.getId(), room.getId()).getUnreadCount()).isEqualTo(6L);
    }

    @Test
    void 메시지를_보내면_그_순번까지_읽은_것으로_본다() {
        Member a = memberService.create("sa@e.com", "1234", "sa");
        Member b = memberService.create("sb@e.com", "1234", "sb");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);
        for (int i = 0; i < 3; i++) messageService.create("b" + i, null, b.getId(), room.getId(), null);

        var mine = messageService.create("mine", null, a.getId(), room.getId(), null);
        messageService.create("after", null, b.getId(), room.getId(), null);

        var counts = countsFor(a.getId(), room.getId());
        assertThat(counts.getLastReadSeq()).isEqualTo(4L);
        assertThat(counts.getLastReadMessageId()).isEqualTo(mine.getId());
        assertThat(counts.getUnreadCount()).isEqualTo(1L);
    }

    @Test
    void 재전송으로_판정되면_읽은_위치를_바꾸지_않는다() {
        Member a = memberService.create("ra2@e.com", "1234", "ra2");
        Member b = memberService.create("rb2@e.com", "1234", "rb2");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);
        String clientId = "22222222-2222-2222-2222-222222222222";

        messageService.create("first", null, a.getId(), room.getId(), null, clientId);   // seq 1
        messageService.create("b1", null, b.getId(), room.getId(), null);                // seq 2
        messageService.create("b2", null, b.getId(), room.getId(), null);                // seq 3
        messageService.create("first", null, a.getId(), room.getId(), null, clientId);   // 재전송

        var counts = countsFor(a.getId(), room.getId());
        assertThat(counts.getLastReadSeq()).isEqualTo(1L);
        assertThat(counts.getUnreadCount()).isEqualTo(2L);
    }

    @Test
    void 순번을_주면_그_위치까지만_읽고_뒤로_가지_않으며_최신을_넘지_않는다() {
        Member a = memberService.create("qa@e.com", "1234", "qa");
        Member b = memberService.create("qb@e.com", "1234", "qb");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);
        Message third = null;
        for (int i = 1; i <= 5; i++) {
            Message m = messageService.create("b" + i, null, b.getId(), room.getId(), null);
            if (i == 3) third = m;
        }

        chatRoomMemberService.markRead(a.getId(), room.getId(), 3L);
        var counts = countsFor(a.getId(), room.getId());
        assertThat(counts.getUnreadCount()).isEqualTo(2L);
        assertThat(counts.getLastReadMessageId()).isEqualTo(third.getId());

        chatRoomMemberService.markRead(a.getId(), room.getId(), 2L);   // 늦게 도착한 옛 요청
        assertThat(countsFor(a.getId(), room.getId()).getLastReadSeq()).isEqualTo(3L);

        chatRoomMemberService.markRead(a.getId(), room.getId(), 99L);  // 최신(5)을 넘는 값
        assertThat(countsFor(a.getId(), room.getId()).getLastReadSeq()).isEqualTo(5L);
        assertThat(countsFor(a.getId(), room.getId()).getUnreadCount()).isZero();
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
