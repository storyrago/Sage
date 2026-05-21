package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@Transactional
public class MessageServiceTest {
    @Autowired
    MessageService messageService;

    @Autowired
    MemberService memberService;

    @Autowired
    ChatRoomService chatRoomService;

    @Autowired
    ChatRoomMemberService chatRoomMemberService;

    @Test
    void 메시지_생성_및_조회() {

        // given
        Member member = memberService.create("test@email.com", "1234", "nick");
        ChatRoom chatRoom = chatRoomService.create("room1");
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        messageService.create("1번", member.getId(), chatRoom.getId());
        messageService.create("2번", member.getId(), chatRoom.getId());
        messageService.create("3번", member.getId(), chatRoom.getId());

        // when
        List<Message> messages = messageService.getAllChatRoomMessages(chatRoom.getId());

        // then
        assertThat(messages.size()).isEqualTo(3);
        assertThat(messages.get(0).getContent()).isEqualTo("1번");
        assertThat(messages.get(1).getContent()).isEqualTo("2번");
        assertThat(messages.get(2).getContent()).isEqualTo("3번");
    }

    @Test
    void 메시지_단건_조회() {
        // given
        Member member = memberService.create("test2@email.com", "1234", "nick2");
        ChatRoom chatRoom = chatRoomService.create("room2");
        chatRoomMemberService.join(member.getId(), chatRoom.getId());
        Message savedMessage = messageService.create("Hello", member.getId(), chatRoom.getId());

        // when
        Message findMessage = messageService.getMessageById(savedMessage.getId());

        // then
        assertThat(findMessage.getContent()).isEqualTo("Hello");
        assertThat(findMessage.getMember().getEmail()).isEqualTo("test2@email.com");
    }
}
