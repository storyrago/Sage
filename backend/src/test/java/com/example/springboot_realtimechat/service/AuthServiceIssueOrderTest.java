package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.auth.dto.LoginRequest;
import com.example.springboot_realtimechat.domain.auth.service.AuthService;
import com.example.springboot_realtimechat.domain.auth.service.LoginRateLimiter;
import com.example.springboot_realtimechat.domain.auth.service.OAuthCodeStore;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.jwt.JwtTokenProvider;
import com.example.springboot_realtimechat.global.jwt.TokenDenylist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

// 토큰을 발급하는 두 경로(로그인, OAuth 코드 교환) 모두 회원 단위 무효화 해제가
// 액세스 토큰 생성보다 먼저 일어나야 한다. 순서가 바뀌면 그 사이에 끼어든
// AuthService.logout(jti 없는 토큰)의 revokeMember가 방금 건 해제로 지워진다.
@ExtendWith(MockitoExtension.class)
class AuthServiceIssueOrderTest {

    @Mock MemberRepository memberRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock LoginRateLimiter loginRateLimiter;
    @Mock TokenDenylist tokenDenylist;
    @Mock OAuthCodeStore oAuthCodeStore;
    @InjectMocks AuthService authService;

    @Test
    void 코드_교환은_무효화_해제_후_토큰을_발급한다() {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);
        given(member.getEmail()).willReturn("e@x.com");
        given(oAuthCodeStore.consume("one-time-code")).willReturn(42L);
        given(memberRepository.findById(42L)).willReturn(Optional.of(member));

        authService.exchangeOAuthCode("one-time-code");

        InOrder inOrder = inOrder(tokenDenylist, jwtTokenProvider);
        inOrder.verify(tokenDenylist).clearMember(42L);
        inOrder.verify(jwtTokenProvider).createAccessToken(42L, "e@x.com");
    }

    @Test
    void 로그인은_무효화_해제_후_토큰을_발급한다() {
        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);
        given(member.getEmail()).willReturn("e@x.com");
        given(member.getPassword()).willReturn("encoded");
        given(loginRateLimiter.isBlocked("127.0.0.1")).willReturn(false);
        given(memberRepository.findByEmail("e@x.com")).willReturn(Optional.of(member));
        given(passwordEncoder.matches("pw", "encoded")).willReturn(true);

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("e@x.com");
        loginRequest.setPassword("pw");

        authService.login(loginRequest, "127.0.0.1");

        InOrder inOrder = inOrder(tokenDenylist, jwtTokenProvider);
        inOrder.verify(tokenDenylist).clearMember(42L);
        inOrder.verify(jwtTokenProvider).createAccessToken(42L, "e@x.com");
    }

    @Test
    void 코드_조회_실패는_401로_바뀌지_않고_그대로_올라간다() {
        RuntimeException redisDown = new RuntimeException("redis down");
        given(oAuthCodeStore.consume("one-time-code")).willThrow(redisDown);

        assertThatThrownBy(() -> authService.exchangeOAuthCode("one-time-code"))
                .isSameAs(redisDown)
                .isNotInstanceOf(CustomException.class);
    }
}
