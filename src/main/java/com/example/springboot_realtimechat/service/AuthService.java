package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.LoginRequest;
import com.example.springboot_realtimechat.dto.LoginResponse;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.security.JwtTokenProvider;
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

    public LoginResponse login(LoginRequest loginRequest, String clientIp){
        if (loginRateLimiter.isBlocked(clientIp)) {
            throw new CustomException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }
        Member member = memberRepository.findByEmail(loginRequest.getEmail()).orElse(null);
        if (member == null || !passwordEncoder.matches(loginRequest.getPassword(), member.getPassword())) {
            loginRateLimiter.recordFailure(clientIp);
            throw new CustomException(member == null ? ErrorCode.MEMBER_NOT_FOUND : ErrorCode.INVALID_PASSWORD);
        }
        loginRateLimiter.reset(clientIp);
        String accessToken = jwtTokenProvider.createAccessToken(member.getId(), member.getEmail());
        return new LoginResponse(accessToken);
    }
}
