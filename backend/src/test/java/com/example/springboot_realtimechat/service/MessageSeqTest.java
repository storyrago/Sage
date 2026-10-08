package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MessageSeqTest {

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @Test
    void 방마다_순번이_1부터_따로_증가한다() {
        Member member = memberService.create("seq-unit@e.com", "1234", "순번");
        ChatRoom roomA = chatRoomService.create("순번방A", false, null);
        ChatRoom roomB = chatRoomService.create("순번방B", false, null);
        chatRoomMemberService.join(member.getId(), roomA.getId(), null);
        chatRoomMemberService.join(member.getId(), roomB.getId(), null);

        Message a1 = messageService.create("a1", null, member.getId(), roomA.getId(), null);
        Message b1 = messageService.create("b1", null, member.getId(), roomB.getId(), null);
        Message a2 = messageService.create("a2", null, member.getId(), roomA.getId(), null);

        assertThat(a1.getSeq()).isEqualTo(1L);
        assertThat(a2.getSeq()).isEqualTo(2L);
        assertThat(b1.getSeq()).isEqualTo(1L);
        assertThat(roomA.getLastMessageSeq()).isEqualTo(2L);
    }

    @Test
    void 응답에_순번이_실린다() {
        Member member = memberService.create("seq-response@e.com", "1234", "순번응답");
        ChatRoom room = chatRoomService.create("순번응답방", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);

        Message message = messageService.create("hi", null, member.getId(), room.getId(), null);

        assertThat(MessageResponse.from(message).getSeq()).isEqualTo(1L);
    }
}
