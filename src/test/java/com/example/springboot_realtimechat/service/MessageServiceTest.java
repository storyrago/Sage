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
import java.util.UUID;

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
        Message saved = messageService.create(null, uploadedUrl("rooms/", member.getId()),
                member.getId(), chatRoom.getId(), null);

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

    // S3Service.upload가 실제로 만드는 키 형태. 접두사 검사와 문법 검사를 따로 겨냥하기 위해
    // 접두사만 틀린 케이스도 문법은 맞는 값을 쓴다.
    private static String uploadedUrl(String prefix, Object ownerId) {
        return BUCKET_PREFIX + prefix + ownerId + "/" + UUID.randomUUID() + "_a.png";
    }

    @Test
    void 자신의_채팅_키는_허용된다() {
        Member member = memberService.create("owner@email.com", "1234", "owner");
        ChatRoom chatRoom = chatRoomService.create("room5", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String ownKey = uploadedUrl("rooms/", member.getId());

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
        String otherKey = uploadedUrl("rooms/", other.getId());

        assertThatThrownBy(() -> messageService.create(null, otherKey, owner.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void profiles_키는_채팅_이미지로_거절된다() {
        Member member = memberService.create("profileuser@email.com", "1234", "pu");
        ChatRoom chatRoom = chatRoomService.create("room7", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String profileKey = uploadedUrl("profiles/", member.getId());

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

    // 방 전원의 브라우저가 임의 호스트를 치지 않도록, 이미지 참조는 우리 버킷의 본인 키로 한정한다.
    @Test
    void 외부_URL은_거부된다() {
        Member member = memberService.create("extuser@email.com", "1234", "eu");
        ChatRoom chatRoom = chatRoomService.create("room9", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String externalUrl = "https://example.com/somewhere/a.png";

        assertThatThrownBy(() -> messageService.create(null, externalUrl, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    @Test
    void null_또는_빈_imageUrl은_허용된다() {
        Member member = memberService.create("blankuser@email.com", "1234", "bu");
        ChatRoom chatRoom = chatRoomService.create("room10", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

        Message saved = messageService.create("빈 이미지", "", member.getId(), chatRoom.getId(), null);

        assertThat(saved.getImageUrl()).isEmpty();
    }

    // 접두사 비교에 구분자 "/"가 없으면 "rooms/{id}9".startsWith("rooms/{id}")가 참이 되어,
    // 자신의 아이디가 문자열로 다른 회원 폴더의 접두사가 되는 순간 그 폴더도 자기 것처럼 통과한다.
    // 실제 DB 아이디 값은 시퀀스라 예측할 수 없으므로, 회원 자신의 아이디에 숫자를 이어붙여
    // "존재하지 않지만 내 아이디를 접두사로 갖는 폴더"를 만들어 결정적으로 재현한다.
    @Test
    void 자신의_아이디를_접두사로_갖는_다른_폴더_키는_거절된다() {
        Member member = memberService.create("prefixvictim@email.com", "1234", "pv");
        ChatRoom chatRoom = chatRoomService.create("room11", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);

        String collidingKey = uploadedUrl("rooms/", member.getId() + "9");

        assertThatThrownBy(() -> messageService.create(null, collidingKey, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    // 같은 객체를 가리키더라도 우리가 만들지 않은 URL 형태는 소유를 확인할 수 없다.
    @Test
    void 경로형_URL은_거부된다() {
        Member member = memberService.create("pathstyle@email.com", "1234", "ps");
        ChatRoom chatRoom = chatRoomService.create("room12", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String pathStyleUrl = "https://s3.ap-northeast-2.amazonaws.com/test-bucket/rooms/999/x.png";

        assertThatThrownBy(() -> messageService.create(null, pathStyleUrl, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }

    // 발신자 자신의 접두사로 시작하더라도 업로드가 만들 수 없는 키 형태는 받지 않는다.
    @Test
    void 자신의_접두사_안의_dot_dot_경로는_거부된다() {
        Member member = memberService.create("dotdot@email.com", "1234", "dd");
        ChatRoom chatRoom = chatRoomService.create("room13", false, null);
        chatRoomMemberService.join(member.getId(), chatRoom.getId(), null);
        String traversalKey = BUCKET_PREFIX + "rooms/" + member.getId() + "/../6/secret.png";

        assertThatThrownBy(() -> messageService.create(null, traversalKey, member.getId(), chatRoom.getId(), null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_IMAGE_REFERENCE);
    }
}
