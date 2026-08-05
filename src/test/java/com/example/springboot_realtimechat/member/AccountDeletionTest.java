package com.example.springboot_realtimechat.member;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 커밋되는 테스트다. @Transactional을 붙이면 DELETE/UPDATE가 flush되지 않아
// 외래키 제약이 실제로 평가되지 않는다.
@SpringBootTest
class AccountDeletionTest {

    @Autowired MemberService memberService;
    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;

    @MockitoBean S3Service s3Service;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 답장을_받은_회원도_탈퇴할_수_있다() {
        Member author = memberService.create("del-author@e.com", "1234", "작성자");
        Member replier = memberService.create("del-replier@e.com", "1234", "답장자");
        ChatRoom room = chatRoomService.create("답장방", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(replier.getId(), room.getId());

        Message original = messageService.create("원본", null, author.getId(), room.getId(), null);
        messageService.create("답장", null, replier.getId(), room.getId(), original.getId());

        assertThatCode(() -> memberService.delete(author.getId())).doesNotThrowAnyException();

        assertThat(memberRepository.findById(author.getId())).isEmpty();
    }

    @Test
    void 탈퇴해도_메시지는_남고_작성자만_비워진다() {
        Member author = memberService.create("del2-author@e.com", "1234", "작성자2");
        Member other = memberService.create("del2-other@e.com", "1234", "다른회원2");
        ChatRoom room = chatRoomService.create("보존방", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(other.getId(), room.getId());
        Message message = messageService.create("남을 내용", null, author.getId(), room.getId(), null);

        memberService.delete(author.getId());

        Message kept = messageRepository.findById(message.getId()).orElseThrow();
        assertThat(kept.getContent()).isEqualTo("남을 내용");
        assertThat(kept.getMember()).isNull();

        MessageService.MessagePage page = messageService.getMessages(room.getId(), other.getId(), null, 30);
        assertThat(page.messages()).extracting(Message::getId).contains(message.getId());
    }

    @Test
    void 답장이_가리키던_원본이_그대로_남는다() {
        Member author = memberService.create("del3-author@e.com", "1234", "작성자3");
        Member replier = memberService.create("del3-replier@e.com", "1234", "답장자3");
        ChatRoom room = chatRoomService.create("링크방", false, null);
        chatRoomMemberService.join(author.getId(), room.getId());
        chatRoomMemberService.join(replier.getId(), room.getId());
        Message original = messageService.create("원본", null, author.getId(), room.getId(), null);
        Message reply = messageService.create("답장", null, replier.getId(), room.getId(), original.getId());

        memberService.delete(author.getId());

        Message keptReply = messageRepository.findById(reply.getId()).orElseThrow();
        assertThat(keptReply.getReplyTo()).isNotNull();
        assertThat(keptReply.getReplyTo().getId()).isEqualTo(original.getId());
    }
}
