package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
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
            if (emailVerified && email != null && !email.equals(existing.getEmail())) {
                Member emailOwner = memberRepository.findByEmail(email).orElse(null);
                if (emailOwner == null) {
                    existing.updateEmail(email);
                } else {
                    // 이미 인증된 사용자를 남의 이메일 소유권 문제로 잠그지 않기 위해 갱신만 건너뛰고 로그인은 유지한다
                    log.warn("소셜 로그인 이메일 갱신 건너뜀: 다른 회원이 이미 보유한 이메일 (provider={}, existingMemberId={})",
                            provider, existing.getId());
                }
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
        String cut = base.length() > 20 ? base.substring(0, 20) : base;
        String trimmed = cut.trim();
        return trimmed.isEmpty() ? "user" : trimmed;   // nickname 컬럼은 20자 제한
    }
}
