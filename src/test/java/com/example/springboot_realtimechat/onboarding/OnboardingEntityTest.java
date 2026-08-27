package com.example.springboot_realtimechat.onboarding;

import com.example.springboot_realtimechat.domain.member.dto.MemberResponse;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OnboardingEntityTest {
    @Autowired MemberRepository memberRepository;

    @Test
    void 새로_만든_소셜회원은_온보딩_전이다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-1", "a@x.com", "닉", null));

        assertThat(m.getOnboardedAt()).isNull();
        assertThat(m.isOnboarded()).isFalse();
        assertThat(MemberResponse.from(m).isOnboarded()).isFalse();
    }

    @Test
    void 온보딩을_기록하면_onboarded가_true가_된다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-2", "b@x.com", "닉", null));

        m.markOnboarded();

        assertThat(m.getOnboardedAt()).isNotNull();
        assertThat(m.isOnboarded()).isTrue();
        assertThat(MemberResponse.from(m).isOnboarded()).isTrue();
    }

    @Test
    void 온보딩_기록은_멱등이다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-3", "c@x.com", "닉", null));

        m.markOnboarded();
        var first = m.getOnboardedAt();
        m.markOnboarded();

        assertThat(m.getOnboardedAt()).isEqualTo(first);
    }

    @Test
    void 닉네임을_바꿀_수_있다() {
        Member m = memberRepository.save(
                Member.ofSocial("GOOGLE", "onboarding-sub-4", "d@x.com", "이전", null));

        m.updateNickname("바뀐닉네임");

        assertThat(m.getNickname()).isEqualTo("바뀐닉네임");
    }
}
