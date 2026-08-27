package com.example.springboot_realtimechat.global.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final String COOKIE_NAME = "oauth2_auth_request";
    private static final int COOKIE_MAX_AGE = 180; // 3분

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request).map(c -> verifyAndDeserialize(c.getValue())).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(request, response);
            return;
        }
        byte[] body = serialize(authorizationRequest);
        String value = Base64.getUrlEncoder().encodeToString(hmac(body))
                + "." + Base64.getUrlEncoder().encodeToString(body);
        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());   // https(운영)에서만 Secure, http(로컬)에선 false
        cookie.setMaxAge(COOKIE_MAX_AGE);
        response.addCookie(cookie);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest req = loadAuthorizationRequest(request);
        deleteCookie(request, response);
        return req;
    }

    private Optional<Cookie> getCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return Optional.empty();
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .findFirst();
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response) {
        getCookie(request).ifPresent(c -> {
            Cookie del = new Cookie(COOKIE_NAME, "");
            del.setPath("/");
            del.setHttpOnly(true);
            del.setMaxAge(0);
            response.addCookie(del);
        });
    }

    private byte[] serialize(OAuth2AuthorizationRequest obj) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("authorization request 직렬화 실패", e);
        }
    }

    private OAuth2AuthorizationRequest verifyAndDeserialize(String cookieValue) {
        String[] parts = cookieValue.split("\\.", 2);
        if (parts.length != 2) return null;
        byte[] sig, body;
        try {
            sig = Base64.getUrlDecoder().decode(parts[0]);
            body = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;   // 손상된 base64
        }
        if (!MessageDigest.isEqual(sig, hmac(body))) return null;   // 위변조 → 거부(역직렬화 안 함)
        try (ByteArrayInputStream bis = new ByteArrayInputStream(body);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (OAuth2AuthorizationRequest) ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("쿠키 HMAC 계산 실패", e);
        }
    }
}
