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
    void 주인이_탈퇴하면_남은_멤버에게_승계되고_코드가_회전한다() {
        Member owner = memberService.create("w-owner2@e.com", "1234", "주인2");
        Member guest = memberService.create("w-guest2@e.com", "1234", "손님2");
        ChatRoom room = chatRoomService.create("소유방2", true, owner.getId());
        String oldCode = room.getInviteCode();
        chatRoomMemberService.join(guest.getId(), room.getId(), oldCode);

        memberService.delete(owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getOwner().getId()).isEqualTo(guest.getId());
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.isPrivate()).isTrue();
        assertThat(reloaded.getInviteCode()).isNotNull().isNotEqualTo(oldCode);
    }

    @Test
    void 가장_먼저_참여한_멤버가_승계한다() {
        Member owner = memberService.create("w-owner4@e.com", "1234", "주인4");
        Member first = memberService.create("w-first4@e.com", "1234", "먼저4");
        Member second = memberService.create("w-second4@e.com", "1234", "나중4");
        ChatRoom room = chatRoomService.create("소유방4", false, owner.getId());
        chatRoomMemberService.join(first.getId(), room.getId(), null);
        chatRoomMemberService.join(second.getId(), room.getId(), null);

        memberService.delete(owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getOwner().getId()).isEqualTo(first.getId());
    }

    @Test
    void 승계받은_멤버는_방장_기능을_쓸_수_있다() {
        Member owner = memberService.create("w-owner5@e.com", "1234", "주인5");
        Member guest = memberService.create("w-guest5@e.com", "1234", "손님5");
        ChatRoom room = chatRoomService.create("소유방5", true, owner.getId());
        chatRoomMemberService.join(guest.getId(), room.getId(), room.getInviteCode());

        memberService.delete(owner.getId());

        assertThatCode(() -> chatRoomService.reissueInviteCode(room.getId(), guest.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void 남은_멤버가_없으면_방이_닫힌다() {
        Member owner = memberService.create("w-owner6@e.com", "1234", "주인6");
        ChatRoom room = chatRoomService.create("소유방6", true, owner.getId());

        memberService.delete(owner.getId());

        ChatRoom reloaded = chatRoomRepository.findById(room.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getOwner()).isNull();
        assertThat(reloaded.getInviteCode()).isNull();
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
