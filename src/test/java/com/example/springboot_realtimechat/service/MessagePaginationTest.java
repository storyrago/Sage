package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
public class MessagePaginationTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @Test
    void 커서_페이지네이션_최신부터_과거로() {
        Member member = memberService.create("pg@email.com", "1234", "pg");
        ChatRoom room = chatRoomService.create("pgroom", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        for (int i = 1; i <= 70; i++) {
            messageService.create(i + "번", null, member.getId(), room.getId(), null);
        }

        MessageService.MessagePage p1 = messageService.getMessages(room.getId(), member.getId(), null, 30);
        assertThat(p1.messages()).hasSize(30);
        assertThat(p1.hasMore()).isTrue();
        assertThat(p1.messages().get(0).getContent()).isEqualTo("41번");
        assertThat(p1.messages().get(29).getContent()).isEqualTo("70번");
        assertThat(p1.messages().get(0).getId()).isLessThan(p1.messages().get(29).getId());

        Long before2 = p1.messages().get(0).getId();
        MessageService.MessagePage p2 = messageService.getMessages(room.getId(), member.getId(), before2, 30);
        assertThat(p2.messages()).hasSize(30);
        assertThat(p2.hasMore()).isTrue();
        assertThat(p2.messages().get(0).getContent()).isEqualTo("11번");
        assertThat(p2.messages().get(29).getContent()).isEqualTo("40번");
        assertThat(p2.messages()).noneMatch(m -> m.getContent().equals("41번"));

        Long before3 = p2.messages().get(0).getId();
        MessageService.MessagePage p3 = messageService.getMessages(room.getId(), member.getId(), before3, 30);
        assertThat(p3.messages()).hasSize(10);
        assertThat(p3.hasMore()).isFalse();
        assertThat(p3.messages().get(0).getContent()).isEqualTo("1번");
        assertThat(p3.messages().get(9).getContent()).isEqualTo("10번");
    }

    @Test
    void 비멤버는_메시지조회시_예외() {
        Member owner = memberService.create("owner@email.com", "1234", "owner");
        ChatRoom room = chatRoomService.create("secret", false, null);
        chatRoomMemberService.join(owner.getId(), room.getId(), null);
        messageService.create("secret", null, owner.getId(), room.getId(), null);
        Member outsider = memberService.create("out@email.com", "1234", "out");

        assertThatThrownBy(() -> messageService.getMessages(room.getId(), outsider.getId(), null, 30))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void member_페치조인으로_닉네임_접근가능() {
        Member member = memberService.create("fj@email.com", "1234", "fjnick");
        ChatRoom room = chatRoomService.create("fjroom", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        messageService.create("hi", null, member.getId(), room.getId(), null);

        MessageService.MessagePage p = messageService.getMessages(room.getId(), member.getId(), null, 30);
        assertThat(p.messages().get(0).getMember().getNickname()).isEqualTo("fjnick");
    }
}
