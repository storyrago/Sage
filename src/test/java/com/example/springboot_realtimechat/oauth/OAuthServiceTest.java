package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.service.OAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OAuthServiceTest {
    @Autowired OAuthService oAuthService;
    @Autowired MemberRepository memberRepository;

    @Test
    void 신규_소셜사용자_생성() {
        Member m = oAuthService.upsertOidcUser("GOOGLE", "sub-1", "new@g.com", true, "Alexander Longname", "http://p");

        assertThat(m.getId()).isNotNull();
        assertThat(m.getPassword()).isNull();
        assertThat(m.getProvider()).isEqualTo("GOOGLE");
        assertThat(m.getProviderId()).isEqualTo("sub-1");
        assertThat(m.getEmail()).isEqualTo("new@g.com");
        assertThat(m.getNickname()).isEqualTo("Alexander");   // 10자 절단 후 trim
    }

    @Test
    void 같은_provider와_providerId면_동일회원() {
        Member first = oAuthService.upsertOidcUser("GOOGLE", "sub-2", "a@g.com", true, "밥", "http://p");
        Member again = oAuthService.upsertOidcUser("GOOGLE", "sub-2", "changed@g.com", true, "밥", "http://p");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(again.getEmail()).isEqualTo("changed@g.com");   // 검증된 이메일이면 갱신
    }

    @Test
    void providerId가_같아도_provider가_다르면_별개회원() {
        Member google = oAuthService.upsertOidcUser("GOOGLE", "same-id", "g@x.com", true, "구글", null);
        Member kakao = oAuthService.upsertOidcUser("KAKAO", "same-id", null, false, "카카오", null);

        assertThat(kakao.getId()).isNotEqualTo(google.getId());
    }

    @Test
    void 이메일이_없어도_회원이_생성된다() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-1", null, false, "카카오유저", "http://p");

        assertThat(m.getId()).isNotNull();
        assertThat(m.getEmail()).isNull();
        assertThat(m.getNickname()).isEqualTo("카카오유저");
    }

    @Test
    void 미검증_이메일은_저장하지_않는다() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-2", "unverified@k.com", false, "카카오", null);

        assertThat(m.getEmail()).isNull();
    }

    @Test
    void 검증된_이메일이_기존회원과_충돌하면_거부() {
        oAuthService.upsertOidcUser("GOOGLE", "sub-3", "dup@g.com", true, "먼저", null);

        assertThatThrownBy(() ->
                oAuthService.upsertOidcUser("KAKAO", "kakao-3", "dup@g.com", true, "나중", null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void 닉네임이_없으면_이메일_로컬파트로_폴백() {
        Member m = oAuthService.upsertOidcUser("GOOGLE", "sub-4", "fallback@g.com", true, null, null);

        assertThat(m.getNickname()).isEqualTo("fallback");
    }

    @Test
    void 닉네임과_이메일이_모두_없으면_user로_폴백() {
        Member m = oAuthService.upsertOidcUser("KAKAO", "kakao-4", null, false, null, null);

        assertThat(m.getNickname()).isEqualTo("user");
    }

    @Test
    void 기존회원의_새_이메일이_다른회원과_충돌하면_갱신만_건너뛴다() {
        Member other = oAuthService.upsertOidcUser("KAKAO", "kakao-9", "taken@x.com", true, "다른사람", null);
        Member mine = oAuthService.upsertOidcUser("GOOGLE", "sub-9", "mine@x.com", true, "나", null);

        Member again = oAuthService.upsertOidcUser("GOOGLE", "sub-9", "taken@x.com", true, "나", null);

        assertThat(again.getId()).isEqualTo(mine.getId());        // 로그인 유지
        assertThat(again.getEmail()).isEqualTo("mine@x.com");     // 기존 이메일 보존
        assertThat(other.getEmail()).isEqualTo("taken@x.com");    // 다른 회원 이메일 불변
    }
}
