package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.ImageCleanupListener;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 살아있는 객체에 만료 태그가 붙으면 안 된다. 판단은 DB의 잔여 참조로만 한다.
@SpringBootTest
@Transactional
class ImageReferenceGateTest {

    @Autowired ImageCleanupListener listener;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @MockitoBean S3Service s3Service;

    private static final String URL = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/victim.png";

    @Test
    void 아무도_참조하지_않으면_태깅한다() {
        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service).tagAsOrphan(URL);
    }

    @Test
    void 다른_회원이_프로필로_참조하면_태깅하지_않는다() {
        Member victim = memberService.create("gate-victim@e.com", "1234", "피해자");
        memberService.updateProfileImage(victim.getId(), URL);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 다른_메시지가_참조하면_태깅하지_않는다() {
        Member owner = memberService.create("gate-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("게이트방");
        chatRoomMemberService.join(owner.getId(), room.getId());
        messageService.create(null, URL, owner.getId(), room.getId(), null);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 공격_재현_남의_URL을_프로필에_넣었다_바꿔도_피해자_객체는_태깅되지_않는다() {
        Member victim = memberService.create("atk-victim@e.com", "1234", "피해자");
        memberService.updateProfileImage(victim.getId(), URL);   // 피해자가 참조 중

        Member attacker = memberService.create("atk-attacker@e.com", "1234", "공격자");
        memberService.updateProfileImage(attacker.getId(), URL); // 남의 URL을 자기 프로필로
        memberService.updateProfileImage(attacker.getId(),
                "https://test-bucket.s3.ap-northeast-2.amazonaws.com/other.png"); // 참조 해제 이벤트 유발

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 공격_재현_남의_URL을_메시지에_붙였다_지워도_피해자_객체는_태깅되지_않는다() {
        Member victim = memberService.create("atk2-victim@e.com", "1234", "피해자2");
        memberService.updateProfileImage(victim.getId(), URL);

        Member attacker = memberService.create("atk2-attacker@e.com", "1234", "공격자2");
        ChatRoom room = chatRoomService.create("공격방");
        chatRoomMemberService.join(attacker.getId(), room.getId());
        var message = messageService.create(null, URL, attacker.getId(), room.getId(), null);
        messageService.delete(room.getId(), message.getId(), attacker.getId());

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }
}
