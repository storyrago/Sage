package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 설계 D3: 소셜 로그인도 이메일 로그인과 같은 이유로 회원 단위 무효화를 해제해야 한다.
// 여기서 빠지면 로그아웃 → 소셜 재로그인 경로가 같은 경계에 걸린다.
@ExtendWith(MockitoExtension.class)
class OAuth2SuccessHandlerTest {

    @Mock MemberRepository memberRepository;
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock TokenDenylist tokenDenylist;
    @InjectMocks OAuth2SuccessHandler handler;

    @Test
    void 소셜_로그인_성공시_토큰_발급보다_먼저_회원_단위_무효화를_해제한다() throws Exception {
        ReflectionTestUtils.setField(handler, "frontendUrl", "https://app.example.com");

        Member member = mock(Member.class);
        given(member.getId()).willReturn(42L);
        given(member.getEmail()).willReturn("oauth@example.com");

        OAuth2AuthenticationToken authToken = mock(OAuth2AuthenticationToken.class);
        OidcUser oidcUser = mock(OidcUser.class);
        given(authToken.getAuthorizedClientRegistrationId()).willReturn("google");
        given(authToken.getPrincipal()).willReturn(oidcUser);
        given(oidcUser.getSubject()).willReturn("google-subject-1");
        given(memberRepository.findByProviderAndProviderId("GOOGLE", "google-subject-1"))
                .willReturn(Optional.of(member));
        given(jwtTokenProvider.createAccessToken(42L, "oauth@example.com")).willReturn("issued-token");

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        handler.onAuthenticationSuccess(request, response, authToken);

        InOrder inOrder = inOrder(tokenDenylist, jwtTokenProvider);
        inOrder.verify(tokenDenylist).clearMember(42L);
        inOrder.verify(jwtTokenProvider).createAccessToken(42L, "oauth@example.com");
        verify(response).sendRedirect("https://app.example.com/#token=issued-token");
    }
}
