package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.ImageReferences;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// 태깅 판단의 근거는 호출자가 준 URL이 아니라 DB의 잔여 참조다.
@SpringBootTest
@Transactional
class ImageReferencesTest {

    @Autowired ImageReferences imageReferences;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @MockitoBean S3Service s3Service;   // 실제 S3를 부르지 않는다

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/photo.png";

    @Test
    void 프로필이_참조하면_참조된_것이다() {
        Member member = memberService.create("ref1@e.com", "1234", "ref1");
        memberService.updateProfileImage(member.getId(), URL);

        assertThat(imageReferences.isReferenced(URL)).isTrue();
    }

    @Test
    void 메시지가_참조하면_참조된_것이다() {
        Member member = memberService.create("ref2@e.com", "1234", "ref2");
        ChatRoom room = chatRoomService.create("이미지방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create(null, URL, member.getId(), room.getId(), null);

        assertThat(imageReferences.isReferenced(URL)).isTrue();
    }

    @Test
    void 아무도_참조하지_않으면_참조되지_않은_것이다() {
        assertThat(imageReferences.isReferenced(URL)).isFalse();
    }

    @Test
    void 본문에_URL이_포함된_메시지가_있으면_참조된_것이다() {
        Member member = memberService.create("ref4@e.com", "1234", "ref4");
        ChatRoom room = chatRoomService.create("본문방");
        chatRoomMemberService.join(member.getId(), room.getId());
        messageService.create("사진 공유 " + URL, null, member.getId(), room.getId(), null);

        assertThat(imageReferences.isReferenced(URL)).isTrue();
    }

    @Test
    void 소프트_삭제된_메시지는_참조로_세지_않는다() {
        Member member = memberService.create("ref3@e.com", "1234", "ref3");
        ChatRoom room = chatRoomService.create("삭제방");
        chatRoomMemberService.join(member.getId(), room.getId());
        Message message = messageService.create(null, URL, member.getId(), room.getId(), null);

        messageService.delete(room.getId(), message.getId(), member.getId());

        assertThat(imageReferences.isReferenced(URL)).isFalse();
    }

    @Test
    void null과_빈_문자열은_참조되지_않은_것으로_본다() {
        assertThat(imageReferences.isReferenced(null)).isFalse();
        assertThat(imageReferences.isReferenced("  ")).isFalse();
    }
}
