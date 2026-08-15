package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.MessageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RoomInfoLeakTest {

    @Autowired MessageService messageService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired MemberService memberService;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 비멤버는_메시지의_방_소속을_응답_코드로_알아낼_수_없다() {
        Member insider = memberService.create("leak-in@e.com", "1234", "안");
        Member outsider = memberService.create("leak-out@e.com", "1234", "밖");
        ChatRoom secret = chatRoomService.create("비밀방", true, insider.getId());
        Message message = messageService.create("비밀", null, insider.getId(), secret.getId(), null);

        // 그 방에 실제로 있는 메시지도, 없는 id도 같은 오류여야 한다.
        assertThatThrownBy(() -> messageService.update(secret.getId(), message.getId(), outsider.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);

        assertThatThrownBy(() -> messageService.update(secret.getId(), 999999L, outsider.getId(), "고침"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_JOINED_ROOM);
    }
}
