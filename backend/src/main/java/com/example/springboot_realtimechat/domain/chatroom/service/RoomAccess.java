package com.example.springboot_realtimechat.domain.chatroom.service;

import com.example.springboot_realtimechat.domain.chatroom.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;

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

    /** 멤버가 아니면 거부한다. 서비스의 멤버 확인은 모두 이 메서드를 쓴다. */
    @Transactional(readOnly = true)
    public void requireMember(Long memberId, Long chatRoomId) {
        if (!isMember(memberId, chatRoomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
    }
}
