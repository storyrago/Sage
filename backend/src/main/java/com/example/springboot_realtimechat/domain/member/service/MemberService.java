package com.example.springboot_realtimechat.domain.member.service;

import com.example.springboot_realtimechat.domain.image.event.ImageDereferencedEvent;
import com.example.springboot_realtimechat.domain.image.service.ImageUploads;
import com.example.springboot_realtimechat.domain.image.service.S3Service;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.event.MemberDeletedEvent;
import com.example.springboot_realtimechat.domain.member.repository.MemberRepository;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final S3Service s3Service;

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
        // 아바타로 지정할 수 있는 것은 본인이 프로필 용도로 올린 이미지뿐이다.
        // 컨트롤러가 아니라 여기에 둬야 이 메서드를 부르는 모든 경로가 같은 검증을 거친다.
        s3Service.requireOwnKey(imageUrl, ImageUploads.Purpose.PROFILE.prefix() + memberId + "/");

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

    /**
     * 회원 행을 지우고 탈퇴 이벤트를 낸다. 채팅방·메시지 쪽 정리는 끝났다고 가정한다 —
     * 탈퇴는 AccountWithdrawalService.withdraw로만 시작한다(ArchitectureTest가 강제).
     */
    @Transactional
    public void deleteRecord(Long id){
        Member member = getMemberById(id);

        // 메시지는 남으므로 그 이미지들은 계속 참조된다. 정리 대상은 프로필 사진뿐이다.
        String profileImageUrl = member.getProfileImageUrl();

        memberRepository.deleteById(id);

        // 보안 동작(구독 회수)을 이미지 정리보다 먼저 실행한다.
        eventPublisher.publishEvent(new MemberDeletedEvent(id));
        if (profileImageUrl != null && !profileImageUrl.isBlank()) {
            eventPublisher.publishEvent(new ImageDereferencedEvent(profileImageUrl));
        }
    }
}
