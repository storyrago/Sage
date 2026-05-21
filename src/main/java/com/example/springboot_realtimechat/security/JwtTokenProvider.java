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
