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

    public LoginResponse login(LoginRequest loginRequest){
        Member member = memberRepository.findByEmail(loginRequest.getEmail())
                .orElseThrow(()-> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        if(!passwordEncoder.matches(loginRequest.getPassword(), member.getPassword())){
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                member.getId(),
                member.getEmail()
        );

        return new LoginResponse(accessToken);
    }
}
