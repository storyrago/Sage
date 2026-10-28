package com.example.springboot_realtimechat.message;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageOwnershipTest {

    private Member member(Long id) {
        Member m = new Member("a@e.com", "pw", "닉");
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    private Message messageBy(Member author) {
        return new Message("내용", null, author, ChatRoom.publicRoom("방", null), null, 1L, null);
    }

    @Test
    void 작성자는_수정_삭제할_수_있다() {
        assertThatCode(() -> messageBy(member(1L)).requireEditableBy(1L)).doesNotThrowAnyException();
    }

    @Test
    void 다른_회원은_NOT_MESSAGE_OWNER로_거부된다() {
        assertThatThrownBy(() -> messageBy(member(1L)).requireEditableBy(2L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 작성자가_없는_메시지는_아무도_수정_삭제할_수_없다() {
        // 탈퇴로 작성자가 지워진 상태를 만든다.
        Message message = messageBy(member(1L));
        ReflectionTestUtils.setField(message, "member", null);

        assertThatThrownBy(() -> message.requireEditableBy(1L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_MESSAGE_OWNER);
    }
}
