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
    public Member upsertGoogleUser(String sub, String email, boolean emailVerified, String name, String picture) {
        // 1) sub 우선 — 이메일이 바뀌었어도 동일인
        Member bySub = memberRepository.findByGoogleSub(sub).orElse(null);
        if (bySub != null) {
            if (email != null && !email.equals(bySub.getEmail())) {
                bySub.updateEmail(email);
            }
            return bySub;
        }
        // 2) 이메일로 기존 회원 — 검증된 이메일에 한해 연결
        Member byEmail = (email != null) ? memberRepository.findByEmail(email).orElse(null) : null;
        if (byEmail != null) {
            if (!emailVerified) {
                throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }
            byEmail.linkGoogle(sub);
            return byEmail;
        }
        // 3) 신규 생성
        Member created = Member.ofGoogle(email, toNickname(name, email), picture, sub);
        return memberRepository.save(created);
    }

    private String toNickname(String name, String email) {
        String base;
        if (name != null && !name.isBlank()) {
            base = name.trim();
        } else if (email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@'));
        } else {
            base = "user";
        }
        String cut = base.length() > 10 ? base.substring(0, 10) : base;
        return cut.trim();   // "Alexander Longname" → 앞 10자 "Alexander " → trim → "Alexander"
    }
}
