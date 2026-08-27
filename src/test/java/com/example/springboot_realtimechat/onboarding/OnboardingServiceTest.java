package com.example.springboot_realtimechat.onboarding;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OnboardingServiceTest {
    @Autowired MemberService memberService;
    @Autowired MemberRepository memberRepository;

    private Member socialMember(String providerId) {
        return memberRepository.save(
                Member.ofSocial("GOOGLE", providerId, providerId + "@x.com", "처음닉", null));
    }

    @Test
    void 닉네임을_수정한다() {
        Member m = socialMember("svc-1");

        Member updated = memberService.updateNickname(m.getId(), "새로운닉네임");

        assertThat(updated.getNickname()).isEqualTo("새로운닉네임");
    }

    @Test
    void 닉네임_앞뒤_공백은_제거한다() {
        Member m = socialMember("svc-2");

        Member updated = memberService.updateNickname(m.getId(), "  공백닉  ");

        assertThat(updated.getNickname()).isEqualTo("공백닉");
    }

    @Test
    void 공백만_있는_닉네임은_거부한다() {
        Member m = socialMember("svc-3");

        assertThatThrownBy(() -> memberService.updateNickname(m.getId(), "   "))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_NICKNAME);
    }

    @Test
    void 스무자를_넘는_닉네임은_거부한다() {
        Member m = socialMember("svc-4");
        String tooLong = "가".repeat(21);

        assertThatThrownBy(() -> memberService.updateNickname(m.getId(), tooLong))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_NICKNAME);
    }

    @Test
    void 정확히_스무자는_허용한다() {
        Member m = socialMember("svc-5");
        String exact = "가".repeat(20);

        Member updated = memberService.updateNickname(m.getId(), exact);

        assertThat(updated.getNickname()).isEqualTo(exact);
    }

    @Test
    void 온보딩을_완료로_기록한다() {
        Member m = socialMember("svc-6");
        assertThat(m.isOnboarded()).isFalse();

        Member done = memberService.completeOnboarding(m.getId());

        assertThat(done.isOnboarded()).isTrue();
    }

    @Test
    void 온보딩_완료_호출은_멱등이다() {
        Member m = socialMember("svc-7");

        var first = memberService.completeOnboarding(m.getId()).getOnboardedAt();
        var second = memberService.completeOnboarding(m.getId()).getOnboardedAt();

        assertThat(second).isEqualTo(first);
    }
}
