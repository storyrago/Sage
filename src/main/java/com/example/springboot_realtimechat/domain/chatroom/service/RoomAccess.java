package com.example.springboot_realtimechat.domain.chatroom.service;

import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "이 회원이 이 방의 멤버인가"를 판단하는 유일한 지점.
 * REST와 WebSocket이 같은 답을 쓰도록 판정을 한 곳에 모은다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomAccess {

    private final ChatRoomMemberRepository chatRoomMemberRepository;

    @Transactional(readOnly = true)
    public boolean isMember(Long memberId, Long chatRoomId) {
        if (memberId == null || chatRoomId == null) {
            log.warn("멤버십 판정에 필요한 식별자가 없음: memberId={}, chatRoomId={}", memberId, chatRoomId);
            return false;
        }
        return chatRoomMemberRepository.existsActiveMembership(memberId, chatRoomId);
    }
}
