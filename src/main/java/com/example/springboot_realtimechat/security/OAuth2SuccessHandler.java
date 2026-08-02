package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {
    private final MemberRepository memberRepository;
    private final OAuthCodeStore oAuthCodeStore;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken authToken = (OAuth2AuthenticationToken) authentication;
        String provider = authToken.getAuthorizedClientRegistrationId().toUpperCase();
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();

        var member = memberRepository.findByProviderAndProviderId(provider, oidcUser.getSubject())
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        // 토큰을 URL에 싣지 않는다. 리다이렉트를 만드는 302의 Location 헤더는 로그에 남는다.
        String code;
        try {
            code = oAuthCodeStore.issue(member.getId());
        } catch (Exception e) {
            // 코드를 저장하지 못하면 로그인이 성립하지 않는다. 실패 경로와 같은 형태로 안내한다.
            log.error("소셜 로그인 코드 발급 실패: memberId={}", member.getId(), e);
            response.sendRedirect(frontendUrl + "/#oauth_error=oauth_failed");
            return;
        }
        response.sendRedirect(frontendUrl + "/#code=" + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }
}
