package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import com.example.springboot_realtimechat.service.S3Service;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 탈퇴한 회원의 메시지는 작성자 없이 남는다. 조회에서 빠지거나 NPE가 나면 안 된다.
@SpringBootTest
@Transactional
class AnonymousAuthorTest {

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MessageRepository messageRepository;
    @Autowired EntityManager entityManager;

    @MockitoBean S3Service s3Service;

    private Member reader;
    private Long roomId;
    private Long anonymousMessageId;

    @BeforeEach
    void setUp() {
        Member author = memberService.create("anon-author@e.com", "1234", "작성자");
        reader = memberService.create("anon-reader@e.com", "1234", "읽는이");
        ChatRoom room = chatRoomService.create("익명방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(author.getId(), roomId, null);
        chatRoomMemberService.join(reader.getId(), roomId, null);

        Message message = messageService.create("남는 메시지", null, author.getId(), roomId, null);
        anonymousMessageId = message.getId();

        // 작성자만 떼어낸다. Task 2의 탈퇴가 만들 상태를 미리 만든다.
        entityManager.createQuery("UPDATE Message m SET m.member = null WHERE m.id = :id")
                .setParameter("id", anonymousMessageId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void 작성자가_없는_메시지도_목록에_포함된다() {
        MessageService.MessagePage page = messageService.getMessages(roomId, reader.getId(), null, 30);

        assertThat(page.messages()).extracting(Message::getId).contains(anonymousMessageId);
    }

    @Test
    void 작성자가_없는_메시지의_응답은_작성자_필드가_비어_있다() {
        Message message = messageRepository.findById(anonymousMessageId).orElseThrow();

        MessageResponse response = MessageResponse.from(message);

        assertThat(response.getMemberId()).isNull();
        assertThat(response.getNickname()).isNull();
        assertThat(response.getProfileImageUrl()).isNull();
        assertThat(response.getContent()).isEqualTo("남는 메시지");
    }

    @Test
    void 작성자가_없는_메시지는_수정할_수_없다() {
        assertThatThrownBy(() -> messageService.update(roomId, anonymousMessageId, reader.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 작성자가_없는_메시지는_삭제할_수_없다() {
        assertThatThrownBy(() -> messageService.delete(roomId, anonymousMessageId, reader.getId()))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_MESSAGE_OWNER);
    }
}
