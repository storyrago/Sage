package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OAuthService {
    private final MemberRepository memberRepository;

    @Transactional
    public Member upsertOidcUser(String provider, String providerId, String email,
                                 boolean emailVerified, String nickname, String picture) {
        // 1) provider + providerId가 신원. 이메일이 바뀌어도 동일인
        Member existing = memberRepository.findByProviderAndProviderId(provider, providerId).orElse(null);
        if (existing != null) {
            if (emailVerified && email != null && !email.equals(existing.getEmail())
                    && memberRepository.findByEmail(email).isEmpty()) {
                existing.updateEmail(email);
            }
            return existing;
        }

        // 2) 검증된 이메일만 저장한다. 미검증 값이 UNIQUE 슬롯을 선점하지 못하게 한다
        String emailToStore = null;
        if (emailVerified && email != null) {
            if (memberRepository.findByEmail(email).isPresent()) {
                throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            emailToStore = email;
        }

        Member created = Member.ofSocial(provider, providerId, emailToStore, toNickname(nickname, email), picture);
        return memberRepository.save(created);
    }

    private String toNickname(String nickname, String email) {
        String base;
        if (nickname != null && !nickname.isBlank()) {
            base = nickname.trim();
        } else if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else {
            base = "user";
        }
        String cut = base.length() > 10 ? base.substring(0, 10) : base;
        String trimmed = cut.trim();
        return trimmed.isEmpty() ? "user" : trimmed;   // nickname 컬럼은 10자 제한
    }
}
