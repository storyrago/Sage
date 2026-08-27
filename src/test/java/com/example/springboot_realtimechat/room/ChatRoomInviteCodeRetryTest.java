package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.chatroom.service.InviteCodeGenerator;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// InviteCodeGenerator를 갈아끼워 사전 확인 경로를 강제로 태운다.
// RoomCreationTest와 분리한 이유: 이 클래스 안의 모든 테스트가 같은 목 빈을 공유하게 되므로,
// 실제 랜덤 코드를 검증하는 테스트와 섞으면 그 테스트들도 목의 영향을 받는다.
@SpringBootTest
class ChatRoomInviteCodeRetryTest {

    @Autowired ChatRoomService chatRoomService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired MemberRepository memberRepository;

    @MockitoBean InviteCodeGenerator inviteCodeGenerator;

    @AfterEach
    void tearDown() {
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 사전_확인이_겹친_코드를_걸러내고_새_코드로_저장한다() {
        Member owner = memberService.create("rc-collide@e.com", "1234", "주인");
        when(inviteCodeGenerator.generate())
                .thenReturn("AAAAAAAAAAAA", "AAAAAAAAAAAA", "BBBBBBBBBBBB");

        ChatRoom first = chatRoomService.create("잠긴방1", true, owner.getId());

        AtomicReference<ChatRoom> second = new AtomicReference<>();
        assertThatCode(() -> second.set(chatRoomService.create("잠긴방2", true, owner.getId())))
                .doesNotThrowAnyException();

        assertThat(first.getInviteCode()).isEqualTo("AAAAAAAAAAAA");
        assertThat(second.get().getInviteCode())
                .isEqualTo("BBBBBBBBBBBB")
                .isNotEqualTo(first.getInviteCode());
    }

    @Test
    void 사전_확인을_다_소진하면_내부_오류를_던진다() {
        Member owner = memberService.create("rc-exhaust@e.com", "1234", "주인2");
        when(inviteCodeGenerator.generate()).thenReturn("CCCCCCCCCCCC");
        chatRoomService.create("먼저_차지한_방", true, owner.getId());

        assertThatThrownBy(() -> chatRoomService.create("절대_못_만드는_방", true, owner.getId()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
