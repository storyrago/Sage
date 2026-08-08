package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomBan;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 커밋되는 테스트다. @Transactional을 붙이면 외래키 제약이 실제로 평가되지 않는다.
@SpringBootTest
class OwnerWithdrawalTest {

    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired ChatRoomRepository chatRoomRepository;
    @Autowired ChatRoomMemberRepository chatRoomMemberRepository;
    @Autowired ChatRoomBanRepository chatRoomBanRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired MemberRepository memberRepository;

    @MockitoBean S3Service s3Service;

    @AfterEach
    void tearDown() {
        chatRoomBanRepository.deleteAll();
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void 방을_소유한_회원도_탈퇴할_수_있다() {
        Member owner = memberService.create("w-owner@e.com", "1234", "주인");
        chatRoomService.create("소유방", true, owner.getId());

        assertThatCode(() -> memberService.delete(owner.getId())).doesNotThrowAnyException();
    }

    @Test
    void 주인이_탈퇴하면_방은_남고_코드가_회수된다() {
        Member owner = memberService.create("w-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("w-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("소유방2", true, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode());

        memberService.delete(owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getCreatedBy()).isNull();
        assertThat(reloaded.getInviteCode()).isNull();
        assertThat(reloaded.isPrivate()).isTrue();
        assertThat(chatRoomMemberRepository.existsByMemberIdAndChatRoomId(guest.getId(), room.getId()))
                .isTrue();
    }

    @Test
    void 강퇴당한_적_있는_회원도_탈퇴할_수_있다() {
        Member owner = memberService.create("w-owner3@e.com", "1234", "주인3");
        Member banned = memberService.create("w-banned3@e.com", "1234", "강퇴됨3");
        ChatRoom room = chatRoomService.create("소유방3", false, owner.getId());
        chatRoomBanRepository.save(new ChatRoomBan(room.getId(), banned.getId()));

        assertThatCode(() -> memberService.delete(banned.getId())).doesNotThrowAnyException();
        assertThat(chatRoomBanRepository.existsByChatRoomIdAndMemberId(room.getId(), banned.getId()))
                .isFalse();
    }
}
