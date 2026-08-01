package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이 이미지 URL을 아직 참조하는 행이 있는지 판단하는 유일한 지점.
 * 태깅 판단의 근거를 호출자가 준 문자열이 아니라 DB 상태로 만든다.
 */
@Component
@RequiredArgsConstructor
public class ImageReferences {

    private final MemberRepository memberRepository;
    private final MessageRepository messageRepository;

    @Transactional(readOnly = true)
    public boolean isReferenced(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return memberRepository.existsByProfileImageUrl(url)
                || messageRepository.existsByImageUrl(url)
                || messageRepository.existsByContentContaining(url);
    }
}
