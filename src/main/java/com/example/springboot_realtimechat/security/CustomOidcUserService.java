package com.example.springboot_realtimechat.security;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {
    private final OAuthService oAuthService;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);
        String sub = oidcUser.getSubject();
        String email = oidcUser.getEmail();
        boolean emailVerified = Boolean.TRUE.equals(oidcUser.getEmailVerified());
        String name = oidcUser.getFullName();
        String picture = oidcUser.getPicture();
        try {
            oAuthService.upsertGoogleUser(sub, email, emailVerified, name, picture);
        } catch (CustomException e) {
            // 실패 핸들러가 #oauth_error=<코드>로 안내
            throw new OAuth2AuthenticationException(new OAuth2Error(e.getErrorCode().name()), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new OAuth2AuthenticationException(new OAuth2Error("oauth_failed"), e.getMessage(), e);
        }
        return oidcUser;
    }
}
