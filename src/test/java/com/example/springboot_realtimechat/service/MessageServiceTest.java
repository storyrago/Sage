package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        ChatRoom chatRoom = chatRoomService.create("room1", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        messageService.create("1번", null, member.getId(), chatRoom.getId(), null);
        messageService.create("2번", null, member.getId(), chatRoom.getId(), null);
        messageService.create("3번", null, member.getId(), chatRoom.getId(), null);

        // when
        List<Message> messages =
                messageService.getMessages(chatRoom.getId(), member.getId(), null, 30).messages();

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
        ChatRoom chatRoom = chatRoomService.create("room2", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId());
        Message savedMessage = messageService.create("Hello", null, member.getId(), chatRoom.getId(), null);

        // when
        Message findMessage = messageService.getMessageById(savedMessage.getId());

        // then
        assertThat(findMessage.getContent()).isEqualTo("Hello");
        assertThat(findMessage.getMember().getEmail()).isEqualTo("test2@email.com");
    }

    @Test
    void 이미지만_있는_메시지는_content가_빈문자열로_저장된다() {
        // given
        Member member = memberService.create("test3@email.com", "1234", "nick3");
        ChatRoom chatRoom = chatRoomService.create("room3", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        // when
        Message saved = messageService.create(null, "http://image.url/a.png", member.getId(), chatRoom.getId(), null);

        // then
        assertThat(saved.getContent()).isEqualTo("");
    }

    @Test
    void content와_imageUrl이_둘다_비면_거부된다() {
        // given
        Member member = memberService.create("test4@email.com", "1234", "nick4");
        ChatRoom chatRoom = chatRoomService.create("room4", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        // when & then
        assertThatThrownBy(() -> messageService.create(null, null, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMPTY_MESSAGE);
    }
}
