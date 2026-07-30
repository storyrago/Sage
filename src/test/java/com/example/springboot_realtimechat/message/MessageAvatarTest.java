package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class MessageAvatarTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @Test
    void 프로필사진이_있으면_메시지응답에_profileImageUrl이_실린다() {
        Member author = memberService.create("avatar-a@e.com", "1234", "author");
        memberService.updateProfileImage(author.getId(), "https://example.com/avatar.png");
        ChatRoom room = chatRoomService.create("avatar-room");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void 프로필사진이_없으면_profileImageUrl은_null이다() {
        Member author = memberService.create("avatar-b@e.com", "1234", "no-photo");
        ChatRoom room = chatRoomService.create("avatar-room-2");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isNull();
    }

    @Test
    void 사진을_바꾸면_과거_메시지_응답도_새_사진을_싣는다() {
        Member author = memberService.create("avatar-c@e.com", "1234", "changer");
        ChatRoom room = chatRoomService.create("avatar-room-3");
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("옛날 메시지", null, author.getId(), room.getId(), null);

        memberService.updateProfileImage(author.getId(), "https://example.com/new.png");

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo("https://example.com/new.png");
    }
}
