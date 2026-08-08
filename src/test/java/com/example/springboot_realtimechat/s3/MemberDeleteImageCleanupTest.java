package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 탈퇴자의 이미지가 버킷에 영원히 남지 않게 한다. 실제 태깅 여부는 리스너의 참조 검사가 정한다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class MemberDeleteImageCleanupTest {

    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ApplicationEvents events;

    @MockitoBean S3Service s3Service;

    private static final String PROFILE = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/profile.png";
    private static final String IMAGE_A = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/a.png";
    private static final String IMAGE_B = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/b.png";

    private List<String> publishedUrls() {
        return events.stream(ImageDereferencedEvent.class).map(ImageDereferencedEvent::url).toList();
    }

    @Test
    void 탈퇴하면_프로필_URL로만_이벤트가_발행된다() {
        Member member = memberService.create("del1@e.com", "1234", "탈퇴자");
        memberService.updateProfileImage(member.getId(), PROFILE);
        ChatRoom room = chatRoomService.create("탈퇴방", false, null);
        chatRoomMemberService.join(member.getId(), room.getId(), null);
        messageService.create(null, IMAGE_A, member.getId(), room.getId(), null);
        messageService.create(null, IMAGE_B, member.getId(), room.getId(), null);

        memberService.delete(member.getId());

        assertThat(publishedUrls()).containsExactly(PROFILE);
    }

    @Test
    void 구독_회수_이벤트가_이미지_정리보다_먼저_발행된다() {
        Member member = memberService.create("del4@e.com", "1234", "탈퇴자4");
        memberService.updateProfileImage(member.getId(), PROFILE);

        memberService.delete(member.getId());

        List<Class<?>> order = events.stream(Object.class)
                .map(Object::getClass)
                .filter(type -> type == MemberDeletedEvent.class || type == ImageDereferencedEvent.class)
                .toList();
        assertThat(order).containsExactly(MemberDeletedEvent.class, ImageDereferencedEvent.class);
    }

    @Test
    void 이미지가_없는_회원의_탈퇴는_이벤트를_발행하지_않는다() {
        Member member = memberService.create("del3@e.com", "1234", "탈퇴자3");

        memberService.delete(member.getId());

        assertThat(publishedUrls()).isEmpty();
    }
}
