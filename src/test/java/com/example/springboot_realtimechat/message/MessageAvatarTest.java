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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class MessageAvatarTest {
    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ObjectMapper objectMapper; // RedisSubscriber가 실제로 주입받는 것과 같은 빈

    @Test
    void 프로필사진이_있으면_메시지응답에_profileImageUrl이_실린다() {
        Member author = memberService.create("avatar-a@e.com", "1234", "author");
        memberService.updateProfileImage(author.getId(), "https://example.com/avatar.png");
        ChatRoom room = chatRoomService.create("avatar-room", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    void 프로필사진이_없으면_profileImageUrl은_null이다() {
        Member author = memberService.create("avatar-b@e.com", "1234", "no-photo");
        ChatRoom room = chatRoomService.create("avatar-room-2", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("안녕", null, author.getId(), room.getId(), null);

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isNull();
    }

    @Test
    void 사진을_바꾸면_과거_메시지_응답도_새_사진을_싣는다() {
        Member author = memberService.create("avatar-c@e.com", "1234", "changer");
        ChatRoom room = chatRoomService.create("avatar-room-3", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("옛날 메시지", null, author.getId(), room.getId(), null);

        memberService.updateProfileImage(author.getId(), "https://example.com/new.png");

        MessageResponse response = MessageResponse.from(msg);

        assertThat(response.getProfileImageUrl()).isEqualTo("https://example.com/new.png");
    }

    // RedisSubscriber.onMessage가 objectMapper.readValue(..., MessageResponse.class)로 실시간 메시지를
    // 역직렬화한다. MessageResponse는 기본 생성자 없이 전체 인자 생성자 하나뿐이라, 이 왕복이 깨지면
    // 유닛 테스트로는 못 잡고 실시간 경로에서만 조용히 터진다.
    @Test
    void 실시간_경로와_동일한_ObjectMapper로_왕복해도_profileImageUrl과_기존_필드가_보존된다() {
        Member author = memberService.create("avatar-d@e.com", "1234", "roundtrip");
        memberService.updateProfileImage(author.getId(), "https://example.com/roundtrip.png");
        ChatRoom room = chatRoomService.create("avatar-room-4", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        Message msg = messageService.create("왕복 테스트", null, author.getId(), room.getId(), null);
        MessageResponse withPhoto = MessageResponse.from(msg);

        MessageResponse roundTripped = objectMapper.readValue(
                objectMapper.writeValueAsBytes(withPhoto), MessageResponse.class);

        assertThat(roundTripped.getProfileImageUrl()).isEqualTo(withPhoto.getProfileImageUrl());
        assertThat(roundTripped.getMessageId()).isEqualTo(withPhoto.getMessageId());
        assertThat(roundTripped.getNickname()).isEqualTo(withPhoto.getNickname());
        assertThat(roundTripped.getImageUrl()).isEqualTo(withPhoto.getImageUrl());
        assertThat(roundTripped.getReplyToId()).isEqualTo(withPhoto.getReplyToId());
        assertThat(roundTripped.isDeleted()).isEqualTo(withPhoto.isDeleted());

        // 사진이 없는 회원(profileImageUrl == null)의 메시지도 왕복에서 null이 그대로 유지되는지 확인
        Member noPhotoAuthor = memberService.create("avatar-e@e.com", "1234", "roundtrip-null");
        chatRoomMemberService.join(noPhotoAuthor.getId(), room.getId());
        Message noPhotoMsg = messageService.create("사진 없음 왕복", null, noPhotoAuthor.getId(), room.getId(), null);
        MessageResponse withoutPhoto = MessageResponse.from(noPhotoMsg);
        assertThat(withoutPhoto.getProfileImageUrl()).isNull();

        MessageResponse roundTrippedWithoutPhoto = objectMapper.readValue(
                objectMapper.writeValueAsBytes(withoutPhoto), MessageResponse.class);

        assertThat(roundTrippedWithoutPhoto.getProfileImageUrl()).isNull();
    }
}
