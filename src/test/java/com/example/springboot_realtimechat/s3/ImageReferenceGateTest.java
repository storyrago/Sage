package com.example.springboot_realtimechat.s3;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.image.event.ImageCleanupListener;
import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.repository.MessageRepository;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 살아있는 객체에 만료 태그가 붙으면 안 된다. 판단은 DB의 잔여 참조로만 한다.
// 참조를 심을 때는 리포지토리로 직접 저장한다. 쓰기 경계를 지나는 서비스로 심으면
// 이 파일이 검증하려는 참조 게이트가 아니라 쓰기 경계를 검증하게 된다.
@SpringBootTest
@Transactional
class ImageReferenceGateTest {

    @Autowired ImageCleanupListener listener;
    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberRepository memberRepository;
    @Autowired MessageRepository messageRepository;

    @MockitoBean S3Service s3Service;

    private static final String BUCKET_PREFIX = "https://test-bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String URL = BUCKET_PREFIX + "profiles/1/00000000-0000-0000-0000-0000000000c1_victim.png";

    private Member memberReferencing(String email, String nickname, String url) {
        Member member = memberService.create(email, "1234", nickname);
        member.updateProfileImageUrl(url);
        return memberRepository.saveAndFlush(member);
    }

    private Message messageReferencing(Member sender, ChatRoom room, String url) {
        return messageRepository.saveAndFlush(new Message("", url, sender, room, null));
    }

    @Test
    void 아무도_참조하지_않으면_태깅한다() {
        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service).tagAsOrphan(URL);
    }

    @Test
    void 다른_회원이_프로필로_참조하면_태깅하지_않는다() {
        memberReferencing("gate-victim@e.com", "피해자", URL);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 다른_메시지가_참조하면_태깅하지_않는다() {
        Member owner = memberService.create("gate-owner@e.com", "1234", "주인");
        ChatRoom room = chatRoomService.create("게이트방", false, null);
        chatRoomMemberService.join(owner.getId(), room.getId(), null);
        messageReferencing(owner, room, URL);

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 프로필_교체로_참조가_풀려도_다른_행이_참조하면_태깅하지_않는다() {
        memberReferencing("atk-victim@e.com", "피해자", URL);
        Member other = memberReferencing("atk-other@e.com", "다른회원", URL);

        // 자기 소유 키로 아바타를 바꾸면 이전 URL에 대한 참조 해제 이벤트가 나간다.
        memberService.updateProfileImage(other.getId(),
                BUCKET_PREFIX + "profiles/" + other.getId()
                        + "/00000000-0000-0000-0000-0000000000c2_mine.png");

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }

    @Test
    void 메시지_삭제로_참조가_풀려도_다른_행이_참조하면_태깅하지_않는다() {
        memberReferencing("atk2-victim@e.com", "피해자2", URL);

        Member sender = memberService.create("atk2-sender@e.com", "1234", "보낸이2");
        ChatRoom room = chatRoomService.create("삭제방", false, null);
        chatRoomMemberService.join(sender.getId(), room.getId(), null);
        Message message = messageReferencing(sender, room, URL);
        messageService.delete(room.getId(), message.getId(), sender.getId());

        listener.onImageDereferenced(new ImageDereferencedEvent(URL));

        verify(s3Service, never()).tagAsOrphan(anyString());
    }
}
