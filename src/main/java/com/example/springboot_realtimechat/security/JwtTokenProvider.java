package com.example.springboot_realtimechat.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/*
* JWT 인증 흐름
  로그인 성공
  → 서버가 JWT 발급
  → 프론트가 JWT 저장
  → 이후 요청마다 Authorization 헤더에 JWT 포함
  → 서버가 JWT 검증
  → 토큰에서 memberId를 꺼내 현재 사용자로 처리
* */

@Component
public class JwtTokenProvider {

    private final String secret;
    private final long accessTokenExpirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs
    ) {
        this.secret = secret;
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }
    //  로그인 성공 시 access token을 만듦.
    public String createAccessToken(Long memberId, String email) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — 토큰 하나를 지목해 무효화하는 식별자
                .subject(String.valueOf(memberId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(getSigningKey())
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Long getMemberId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    public String getEmail(String token) {
        return parseClaims(token).get("email", String.class);
    }

    /** 토큰의 만료 시각(epoch millis). 파싱할 수 없으면 null. */
    public Long getExpiresAt(String token) {
        try {
            return parseClaims(token).getExpiration().getTime();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토큰의 식별자(jti). 없거나 파싱할 수 없으면 null. */
    public String getJti(String token) {
        try {
            return parseClaims(token).getId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 토큰의 발급 시각(epoch millis). 없거나 파싱할 수 없으면 null. */
    public Long getIssuedAt(String token) {
        try {
            Date issuedAt = parseClaims(token).getIssuedAt();
            return issuedAt != null ? issuedAt.getTime() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
