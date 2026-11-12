package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MessageSeqCursorTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member member;
    private ChatRoom room;

    // 방에 메시지 n개(seq 1..n)를 만든다.
    private void roomWith(int n) {
        member = memberService.create("cursor@e.com", "1234", "cursor");
        room = chatRoomService.create("cursor", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        for (int i = 1; i <= n; i++) messageService.create("m" + i, null, member.getId(), room.getId(), null);
    }

    private MessageService.MessagePage page(Long beforeSeq, Long afterSeq, int limit) {
        return messageService.getMessages(room.getId(), member.getId(), beforeSeq, afterSeq, limit);
    }

    @Test
    void afterSeq는_그_다음부터_오름차순으로_준다() {
        roomWith(10);

        MessageService.MessagePage p = page(null, 3L, 4);

        assertThat(p.messages()).extracting(Message::getSeq).containsExactly(4L, 5L, 6L, 7L);
        assertThat(p.hasMore()).isTrue();
    }

    @Test
    void afterSeq로_끝까지_받으면_hasMore가_false다() {
        roomWith(5);

        MessageService.MessagePage p = page(null, 3L, 4);

        assertThat(p.messages()).extracting(Message::getSeq).containsExactly(4L, 5L);
        assertThat(p.hasMore()).isFalse();
    }

    @Test
    void afterSeq가_최신이면_빈_페이지다() {
        roomWith(3);

        MessageService.MessagePage p = page(null, 3L, 30);

        assertThat(p.messages()).isEmpty();
        assertThat(p.hasMore()).isFalse();
    }

    @Test
    void beforeSeq는_그_이전_최신_limit개를_오름차순으로_준다() {
        roomWith(10);

        MessageService.MessagePage p = page(8L, null, 3);

        assertThat(p.messages()).extracting(Message::getSeq).containsExactly(5L, 6L, 7L);
        assertThat(p.hasMore()).isTrue();
    }

    @Test
    void 커서가_없으면_최신_페이지를_순번_오름차순으로_준다() {
        roomWith(5);

        MessageService.MessagePage p = page(null, null, 3);

        assertThat(p.messages()).extracting(Message::getSeq).containsExactly(3L, 4L, 5L);
        assertThat(p.hasMore()).isTrue();
    }

    @Test
    void 커서를_두_개_주면_INVALID_INPUT_VALUE() {
        roomWith(3);

        assertThatThrownBy(() -> page(2L, 1L, 30))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void 비멤버는_순번_조회도_NOT_JOINED_ROOM() {
        roomWith(3);
        Member outsider = memberService.create("cursor-out@e.com", "1234", "out");

        assertThatThrownBy(() -> messageService.getMessages(room.getId(), outsider.getId(), null, 0L, 30))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_JOINED_ROOM);
    }
}
