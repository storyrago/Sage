package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReplyCountTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private UnreadCountResponse countsFor(Long memberId, Long roomId) {
        List<UnreadCountResponse> counts = chatRoomMemberService.getUnreadCounts(memberId);
        return counts.stream()
                .filter(c -> c.getChatroomId().equals(roomId))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void 내메시지에_달린_남의_답장만_센다() {
        Member a = memberService.create("rc-a@e.com", "1234", "a");
        Member b = memberService.create("rc-b@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장1", null, b.getId(), room.getId(), mine.getId());
        messageService.create("답장2", null, b.getId(), room.getId(), mine.getId());
        messageService.create("답장 아님", null, b.getId(), room.getId(), null);

        UnreadCountResponse a측 = countsFor(a.getId(), room.getId());
        assertThat(a측.getReplyCount()).isEqualTo(2L);
        assertThat(a측.getUnreadCount()).isEqualTo(3L);   // b가 보낸 3개 전부
    }

    @Test
    void 남의_메시지에_달린_답장은_안_센다() {
        Member a = memberService.create("rc-c@e.com", "1234", "a");
        Member b = memberService.create("rc-d@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message bMessage = messageService.create("b 메시지", null, b.getId(), room.getId(), null);
        messageService.create("b가 자기 글에 답장", null, b.getId(), room.getId(), bMessage.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 내가_보낸_답장은_안_센다() {
        Member a = memberService.create("rc-e@e.com", "1234", "a");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("내가 내 글에 답장", null, a.getId(), room.getId(), mine.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 삭제된_답장은_안_센다() {
        Member a = memberService.create("rc-f@e.com", "1234", "a");
        Member b = memberService.create("rc-g@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        Message reply = messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.delete(room.getId(), reply.getId(), b.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isZero();
    }

    @Test
    void 부모메시지가_삭제돼도_답장은_센다() {
        Member a = memberService.create("rc-h@e.com", "1234", "a");
        Member b = memberService.create("rc-i@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.delete(room.getId(), mine.getId(), a.getId());

        assertThat(countsFor(a.getId(), room.getId()).getReplyCount()).isEqualTo(1L);
    }

    @Test
    void 읽으면_답장_카운트도_0이_된다() {
        Member a = memberService.create("rc-j@e.com", "1234", "a");
        Member b = memberService.create("rc-k@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        chatRoomMemberService.markRead(a.getId(), room.getId());

        UnreadCountResponse after = countsFor(a.getId(), room.getId());
        assertThat(after.getReplyCount()).isZero();
        assertThat(after.getUnreadCount()).isZero();
    }

    // 설계 함정 1 회귀: 답장 조인이 INNER로 떨어지면 이 방의 unreadCount가 0으로 뭉개진다.
    @Test
    void 답장이_없는_방의_안읽음이_뭉개지지_않는다() {
        Member a = memberService.create("rc-l@e.com", "1234", "a");
        Member b = memberService.create("rc-m@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        messageService.create("m1", null, b.getId(), room.getId(), null);
        messageService.create("m2", null, b.getId(), room.getId(), null);

        UnreadCountResponse a측 = countsFor(a.getId(), room.getId());
        assertThat(a측.getUnreadCount()).isEqualTo(2L);
        assertThat(a측.getReplyCount()).isZero();
    }

    @Test
    void replyCount는_unreadCount를_넘지_않는다() {
        Member a = memberService.create("rc-n@e.com", "1234", "a");
        Member b = memberService.create("rc-o@e.com", "1234", "b");
        ChatRoom room = chatRoomService.create("room", false, null);
        chatRoomMemberService.join(a.getId(), room.getId(), null);
        chatRoomMemberService.join(b.getId(), room.getId(), null);

        Message mine = messageService.create("내 메시지", null, a.getId(), room.getId(), null);
        messageService.create("답장", null, b.getId(), room.getId(), mine.getId());
        messageService.create("일반", null, b.getId(), room.getId(), null);

        for (UnreadCountResponse c : chatRoomMemberService.getUnreadCounts(a.getId())) {
            assertThat(c.getReplyCount()).isLessThanOrEqualTo(c.getUnreadCount());
        }
    }
}
