package com.example.springboot_realtimechat.message;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class MessageAvatarTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    // 프로필 사진은 본인이 프로필 용도로 올린 키만 지정할 수 있다.
    private static String ownProfileUrl(Long memberId) {
        return "https://test-bucket.s3.ap-northeast-2.amazonaws.com/profiles/"
                + memberId + "/" + UUID.randomUUID() + "_a.png";
    }

    @Test
    void 프로필사진이_있으면_메시지응답에_profileImageUrl이_실린다() {
        Member author = memberService.create("avatar-a@e.com", "1234", "author");
        String avatarUrl = ownProfileUrl(author.getId());
        memberService.updateProfileImage(author.getId(), avatarUrl);
        ChatRoom room = chatRoomService.create("avatar-room", false, null);
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo(avatarUrl);
    }

    @Test
    void 프로필사진이_없으면_profileImageUrl은_null이다() {
        Member author = memberService.create("avatar-b@e.com", "1234", "no-photo");
        ChatRoom room = chatRoomService.create("avatar-room-2", false, null);
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isNull();
    }

    @Test
    void 사진을_바꾸면_과거_메시지_응답도_새_사진을_싣는다() {
        Member author = memberService.create("avatar-c@e.com", "1234", "changer");
        ChatRoom room = chatRoomService.create("avatar-room-3", false, null);
        chatRoomMemberService.join(author.getId(), room.getId(), null);
        Message msg = messageService.create("옛날 메시지", null, author.getId(), room.getId(), null);

        String newUrl = ownProfileUrl(author.getId());
        memberService.updateProfileImage(author.getId(), newUrl);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo(newUrl);
    }
}
