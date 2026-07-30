package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;

    public List<Member> getMemberList(){
        return memberRepository.findAll();
    }

    public Member getMemberById(Long id){
        return memberRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }

    @Transactional
    public Member create(String email, String password, String nickname){
        if (memberRepository.findByEmail(email).isPresent()) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }
        String encodedPassword = passwordEncoder.encode(password);
        Member member = new Member(email, encodedPassword, nickname);
        return memberRepository.save(member);
    }

    @Transactional
    public Member updateProfileImage(Long memberId, String imageUrl) {
        Member member = getMemberById(memberId);        // 기존 메서드 재사용
        String oldUrl = member.getProfileImageUrl();
        member.updateProfileImageUrl(imageUrl);         // 엔티티에 만든 메서드

        // 같은 URL로 다시 저장하는 경로(사진 확정 후 닉네임 저장 실패 → 재시도)에서
        // 이벤트가 나가면 살아있는 사진에 만료 태그가 붙는다.
        if (oldUrl != null && !oldUrl.isBlank() && !oldUrl.equals(imageUrl)) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(oldUrl));
        }
        return member;
    }

    @Transactional
    public Member updateNickname(Long memberId, String nickname) {
        Member member = getMemberById(memberId);
        String trimmed = (nickname == null) ? "" : nickname.trim();
        if (trimmed.isEmpty() || trimmed.length() > 20) {
            throw new CustomException(ErrorCode.INVALID_NICKNAME);
        }
        member.updateNickname(trimmed);
        return member;
    }

    @Transactional
    public Member completeOnboarding(Long memberId) {
        Member member = getMemberById(memberId);
        member.markOnboarded();
        return member;
    }

    @Transactional
    public void delete(Long id){
        Member member = memberRepository.findById(id)
                .orElseThrow(()-> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        chatRoomMemberRepository.deleteByMember(member);
        messageRepository.deleteByMember(member);
        memberRepository.delete(member);
    }
}
