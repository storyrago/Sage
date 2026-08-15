package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.LoginRequest;
import com.example.springboot_realtimechat.dto.LoginResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
import com.example.springboot_realtimechat.security.OAuthCodeStore;
import com.example.springboot_realtimechat.security.TokenDenylist;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginRateLimiter loginRateLimiter;
    private final TokenDenylist tokenDenylist;
    private final OAuthCodeStore oAuthCodeStore;

    public LoginResponse login(LoginRequest loginRequest, String clientIp){
        if (loginRateLimiter.isBlocked(clientIp)) {
            throw new CustomException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }
        Member member = memberRepository.findByEmail(loginRequest.getEmail()).orElse(null);
        if (member == null || member.getPassword() == null
                || !passwordEncoder.matches(loginRequest.getPassword(), member.getPassword())) {
            loginRateLimiter.recordFailure(clientIp);
            throw new CustomException(member == null ? ErrorCode.MEMBER_NOT_FOUND : ErrorCode.INVALID_PASSWORD);
        }
        loginRateLimiter.reset(clientIp);
        // 회원 단위 무효화를 해제한다. iat는 초 단위라, 지우지 않으면 같은 초의 재로그인이 막힌다.
        tokenDenylist.clearMember(member.getId());
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        return new LoginResponse(accessToken);
    }

    public void logout(String token) {
        String jti = jwtTokenProvider.getJti(token);
        Long expiresAt = jwtTokenProvider.getExpiresAt(token);
        if (jti != null && expiresAt != null) {
            tokenDenylist.revokeToken(jti, expiresAt);
            return;
        }
        // jti가 없는 토큰은 하나만 지목할 수 없다. 그 회원 전체를 무효화한다.
        tokenDenylist.revokeMember(jwtTokenProvider.getMemberId(token));
    }

    /** 소셜 로그인 리다이렉트가 실어 온 일회용 코드를 액세스 토큰으로 바꾼다. */
    public LoginResponse exchangeOAuthCode(String code) {
        // 만료·재사용·위조를 구분하지 않는다. 구분하면 그 코드가 존재했는지를 알려주는 것이다.
        Long memberId = oAuthCodeStore.consume(code);
        if (memberId == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));
        // 토큰을 발급하는 경로는 회원 단위 무효화를 해제한다(AuthService.login과 같은 이유).
        tokenDenylist.clearMember(member.getId());
        return new LoginResponse(jwtTokenProvider.createAccessToken(member.getId(), member.getEmail()));
    }
}
