package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OAuthPersistenceTest {
    @Autowired MemberRepository memberRepository;

    @Test
    void 소셜회원_저장_후_provider와_providerId로_조회된다() {
        memberRepository.save(
                Member.ofSocial("GOOGLE", "sub-123", "g@e.com", "구글이", "http://img"));

        Member found = memberRepository.findByProviderAndProviderId("GOOGLE", "sub-123").orElseThrow();

        assertThat(found.getEmail()).isEqualTo("g@e.com");
        assertThat(found.getPassword()).isNull();
        assertThat(found.getProvider()).isEqualTo("GOOGLE");
        assertThat(found.getProviderId()).isEqualTo("sub-123");
    }

    @Test
    void 이메일_없이도_소셜회원을_저장할_수_있다() {
        memberRepository.save(
                Member.ofSocial("KAKAO", "kakao-1", null, "카카오", null));

        Member found = memberRepository.findByProviderAndProviderId("KAKAO", "kakao-1").orElseThrow();

        assertThat(found.getEmail()).isNull();
    }
}
