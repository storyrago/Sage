package com.example.springboot_realtimechat.oauth;

import com.example.springboot_realtimechat.global.auth.HttpCookieOAuth2AuthorizationRequestRepository;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CookieAuthRequestRepositoryTest {

    private static final String COOKIE_NAME = "oauth2_auth_request";

    private HttpCookieOAuth2AuthorizationRequestRepository newRepo() {
        HttpCookieOAuth2AuthorizationRequestRepository repo = new HttpCookieOAuth2AuthorizationRequestRepository();
        ReflectionTestUtils.setField(repo, "secret", "test-secret-key-32-bytes-long-xxxxxxxx");
        return repo;
    }

    private OAuth2AuthorizationRequest sampleRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("cid")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .scope("openid")
                .state("xyz")
                .build();
    }

    private String saveAndExtractCookieValue(HttpCookieOAuth2AuthorizationRequestRepository repo,
                                              OAuth2AuthorizationRequest authorizationRequest) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        repo.saveAuthorizationRequest(authorizationRequest, request, response);
        Cookie cookie = response.getCookie(COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    @Test
    void 정상_쿠키는_왕복하며_원본_요청을_복원한다() {
        HttpCookieOAuth2AuthorizationRequestRepository repo = newRepo();
        OAuth2AuthorizationRequest original = sampleRequest();

        String cookieValue = saveAndExtractCookieValue(repo, original);

        MockHttpServletRequest loadRequest = new MockHttpServletRequest();
        loadRequest.setCookies(new Cookie(COOKIE_NAME, cookieValue));

        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(loadRequest);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo(original.getState());
        assertThat(loaded.getClientId()).isEqualTo(original.getClientId());
    }

    @Test
    void 위조된_쿠키는_역직렬화하지_않고_거부한다() {
        HttpCookieOAuth2AuthorizationRequestRepository repo = newRepo();
        OAuth2AuthorizationRequest original = sampleRequest();

        String cookieValue = saveAndExtractCookieValue(repo, original);

        // body 부분(서명 뒤)의 문자 하나를 뒤집어 위변조
        String[] parts = cookieValue.split("\\.", 2);
        assertThat(parts).hasSize(2);
        char[] bodyChars = parts[1].toCharArray();
        int idx = 0;
        char original0 = bodyChars[idx];
        char replacement = original0 == 'A' ? 'B' : 'A';
        bodyChars[idx] = replacement;
        String tamperedBody = new String(bodyChars);
        String tamperedCookieValue = parts[0] + "." + tamperedBody;

        MockHttpServletRequest loadRequest = new MockHttpServletRequest();
        loadRequest.setCookies(new Cookie(COOKIE_NAME, tamperedCookieValue));

        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(loadRequest);

        assertThat(loaded).isNull();
    }

    @Test
    void 점이_없는_쿠키값은_거부한다() {
        HttpCookieOAuth2AuthorizationRequestRepository repo = newRepo();

        MockHttpServletRequest loadRequest = new MockHttpServletRequest();
        loadRequest.setCookies(new Cookie(COOKIE_NAME, "no-dot-here-at-all"));

        OAuth2AuthorizationRequest loaded = repo.loadAuthorizationRequest(loadRequest);

        assertThat(loaded).isNull();
    }
}
