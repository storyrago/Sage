package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
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
class ServiceFlowTest {

    @Autowired
    MemberService memberService;

    @Autowired
    ChatRoomService chatRoomService;

    @Autowired
    ChatRoomMemberService chatRoomMemberService;

    @Autowired
    MessageService messageService;

    @Test
    void 회원_생성() {
        // when
        Member member = memberService.create("member@email.com", "1234", "nick");

        // then
        assertThat(member.getId()).isNotNull();
        assertThat(member.getEmail()).isEqualTo("member@email.com");
        assertThat(member.getNickname()).isEqualTo("nick");
    }

    @Test
    void 채팅방_생성() {
        // when
        ChatRoom chatRoom = chatRoomService.create("room1");

        // then
        assertThat(chatRoom.getId()).isNotNull();
        assertThat(chatRoom.getName()).isEqualTo("room1");
    }

    @Test
    void 채팅방_참여() {
        // given
        Member member = memberService.create("join@email.com", "1234", "joiner");
        ChatRoom chatRoom = chatRoomService.create("join-room");

        // when
        ChatRoomMember chatRoomMember = chatRoomMemberService.join(member.getId(), chatRoom.getId());

        // then
        assertThat(chatRoomMember.getId()).isNotNull();
        assertThat(chatRoomMember.getMember().getId()).isEqualTo(member.getId());
        assertThat(chatRoomMember.getChatRoom().getId()).isEqualTo(chatRoom.getId());
    }

    @Test
    void 메시지_전송() {
        // given
        Member member = memberService.create("message@email.com", "1234", "sender");
        ChatRoom chatRoom = chatRoomService.create("message-room");
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        // when
        Message message = messageService.create("hello", member.getId(), chatRoom.getId());

        // then
        assertThat(message.getId()).isNotNull();
        assertThat(message.getContent()).isEqualTo("hello");
        assertThat(message.getMember().getId()).isEqualTo(member.getId());
        assertThat(message.getChatRoom().getId()).isEqualTo(chatRoom.getId());
    }

    @Test
    void 미참여자_메시지_전송_실패() {
        // given
        Member member = memberService.create("outsider@email.com", "1234", "outsider");
        ChatRoom chatRoom = chatRoomService.create("private-room");

        // when & then
        assertThatThrownBy(() -> messageService.create("hello", member.getId(), chatRoom.getId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_JOINED_ROOM);
    }

    @Test
    void 중복_참여_실패() {
        // given
        Member member = memberService.create("duplicate@email.com", "1234", "member");
        ChatRoom chatRoom = chatRoomService.create("duplicate-room");
        chatRoomMemberService.join(member.getId(), chatRoom.getId());

        // when & then
        assertThatThrownBy(() -> chatRoomMemberService.join(member.getId(), chatRoom.getId()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_JOINED_ROOM);
    }
}
