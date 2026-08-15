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
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

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
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
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
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

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
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

        // when & then
        assertThatThrownBy(() -> messageService.create(null, null, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMPTY_MESSAGE);
    }

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";

    @Test
    void 자신의_채팅_키는_허용된다() {
        Member member = memberService.create("owner@email.com", "1234", "owner");
        ChatRoom chatRoom = chatRoomService.create("room5", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String ownKey = BUCKET_PREFIX + "rooms/" + member.getId() + "/a.png";

        Message saved = messageService.create(null, ownKey, member.getId(), chatRoom.getId(), null);

        assertThat(saved.getImageUrl()).isEqualTo(ownKey);
    }

    @Test
    void 다른_멤버의_채팅_키는_거절된다() {
        Member owner = memberService.create("owner2@email.com", "1234", "owner2");
        Member other = memberService.create("other@email.com", "1234", "other");
        ChatRoom chatRoom = chatRoomService.create("room6", false, null);
        chatRoomMemberService.join(owner.getId(), chatRoom.getId(), null);
        chatRoomMemberService.join(other.getId(), chatRoom.getId(), null);
        String otherKey = BUCKET_PREFIX + "rooms/" + other.getId() + "/a.png";

        assertThatThrownBy(() -> messageService.create(null, otherKey, owner.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void profiles_키는_채팅_이미지로_거절된다() {
        Member member = memberService.create("profileuser@email.com", "1234", "pu");
        ChatRoom chatRoom = chatRoomService.create("room7", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String profileKey = BUCKET_PREFIX + "profiles/a.png";

        assertThatThrownBy(() -> messageService.create(null, profileKey, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void 레거시_평면_키는_거절된다() {
        Member member = memberService.create("legacyuser@email.com", "1234", "lu");
        ChatRoom chatRoom = chatRoomService.create("room8", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String legacyKey = BUCKET_PREFIX + "abc_photo.png";

        assertThatThrownBy(() -> messageService.create(null, legacyKey, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void 외부_URL은_그대로_허용된다() {
        Member member = memberService.create("extuser@email.com", "1234", "eu");
        ChatRoom chatRoom = chatRoomService.create("room9", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String externalUrl = "https://example.com/somewhere/a.png";

        Message saved = messageService.create(null, externalUrl, member.getId(), chatRoom.getId(), null);

        assertThat(saved.getImageUrl()).isEqualTo(externalUrl);
    }

    @Test
    void null_또는_빈_imageUrl은_허용된다() {
        Member member = memberService.create("blankuser@email.com", "1234", "bu");
        ChatRoom chatRoom = chatRoomService.create("room10", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

        Message saved = messageService.create("빈 이미지", "", member.getId(), chatRoom.getId(), null);

        assertThat(saved.getImageUrl()).isEmpty();
    }
}
