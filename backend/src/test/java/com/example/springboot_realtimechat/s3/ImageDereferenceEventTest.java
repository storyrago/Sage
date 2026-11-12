package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 참조가 끊긴 경우에만 이벤트가 나가야 한다. 같은 URL로 다시 저장하는 경로에서
// 이벤트가 나가면 살아있는 사진에 만료 태그가 붙는다.
@SpringBootTest
@Transactional
@RecordApplicationEvents
class ImageDereferenceEventTest {

    @Autowired MemberService memberService;
    @Autowired ApplicationEvents events;

    @MockitoBean S3Service s3Service;   // 커밋 전에는 호출되지 않아야 한다

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String OLD = BUCKET_PREFIX + "profiles/1/00000000-0000-0000-0000-0000000000a1_old.png";
    private static final String NEW = BUCKET_PREFIX + "profiles/1/00000000-0000-0000-0000-0000000000a2_new.png";

    private List<String> publishedUrls() {
        return events.stream(ImageDereferencedEvent.class).map(ImageDereferencedEvent::url).toList();
    }

    @Test
    void 프로필_사진을_교체하면_옛_URL로_이벤트가_발행된다() {
        Member member = memberService.create("p1@e.com", "1234", "p1");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).containsExactly(OLD);
    }

    @Test
    void 같은_URL로_다시_저장하면_이벤트가_발행되지_않는다() {
        Member member = memberService.create("p2@e.com", "1234", "p2");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), OLD);

        assertThat(publishedUrls()).isEmpty();
    }

    @Test
    void 사진이_없던_회원은_이벤트가_발행되지_않는다() {
        Member member = memberService.create("p3@e.com", "1234", "p3");

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).isEmpty();
    }

    // 이 테스트 클래스는 @Transactional이라 커밋되지 않는다.
    // AFTER_COMMIT 리스너가 실행되지 않는다는 것이 곧 D2가 지켜진다는 증거다.
    @Test
    void 커밋되지_않으면_태깅이_일어나지_않는다() {
        Member member = memberService.create("p4@e.com", "1234", "p4");
        memberService.updateProfileImage(member.getId(), OLD);

        memberService.updateProfileImage(member.getId(), NEW);

        assertThat(publishedUrls()).containsExactly(OLD);      // 이벤트는 발행됐지만
        verify(s3Service, never()).tagAsOrphan(anyString());   // 커밋 전이므로 태깅은 없다
    }
}
