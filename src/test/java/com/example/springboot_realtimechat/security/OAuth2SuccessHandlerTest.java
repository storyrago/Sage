package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 리다이렉트에 자격증명을 싣지 않는다. 302의 Location 헤더는 로그에 남는다.
@ExtendWith(MockitoExtension.class)
class OAuth2SuccessHandlerTest {

    @Mock MemberRepository memberRepository;
    @Mock OAuthCodeStore oAuthCodeStore;
    @InjectMocks OAuth2SuccessHandler handler;

    private OAuth2AuthenticationToken authToken;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "frontendUrl", "https://app.example.com");

        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);

        OidcUser oidcUser = mock(OidcUser.class);
        authToken = mock(OAuth2AuthenticationToken.class);
        given(authToken.getAuthorizedClientRegistrationId()).willReturn("google");
        given(authToken.getPrincipal()).willReturn(oidcUser);
        given(oidcUser.getSubject()).willReturn("google-subject-1");
        given(memberRepository.findByProviderAndProviderId("GOOGLE", "google-subject-1"))
                .willReturn(Optional.of(member));

        response = mock(HttpServletResponse.class);
    }

    @Test
    void 리다이렉트에_토큰이_아니라_코드를_싣는다() throws Exception {
        given(oAuthCodeStore.issue(42L)).willReturn("one-time-code");

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(response).sendRedirect("https://app.example.com/#code=one-time-code");
    }

    @Test
    void 리다이렉트_URL에_JWT가_들어가지_않는다() throws Exception {
        given(oAuthCodeStore.issue(42L)).willReturn("one-time-code");

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(captor.capture());
        assertThat(captor.getValue()).doesNotContain("token=");
    }

    @Test
    void 코드_발급에_실패하면_오류로_리다이렉트한다() throws Exception {
        willThrow(new RuntimeException("redis down")).given(oAuthCodeStore).issue(42L);

        handler.onAuthenticationSuccess(mock(HttpServletRequest.class), response, authToken);

        verify(response).sendRedirect("https://app.example.com/#oauth_error=oauth_failed");
    }
}
