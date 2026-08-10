package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.event.RoomDeletedEvent;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.ChatRoomRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberRepository memberRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final ApplicationEventPublisher eventPublisher;

    // 코드 충돌은 UNIQUE 제약이 막는다. 확률이 극히 낮아 재시도 횟수는 작게 잡는다.
    private static final int CODE_RETRY = 5;

    /**
     * ownerId가 null이면 주인 없는 방을 만든다. 테스트 픽스처와 시드 방이 그 경우다.
     * 생성자는 주인이자 첫 멤버가 된다 — 잠긴 방에서는 별도 join이 코드 없이 거부되기 때문이다.
     */
    @Transactional
    public ChatRoom create(String name, boolean isPrivate, Long ownerId) {
        Member owner = ownerId == null ? null : memberRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));

        ChatRoom saved = saveWithCode(name, isPrivate, owner);

        if (owner != null) {
            chatRoomMemberRepository.save(new ChatRoomMember(owner, saved));
        }
        return saved;
    }

    private ChatRoom saveWithCode(String name, boolean isPrivate, Member owner) {
        if (!isPrivate) {
            return chatRoomRepository.save(ChatRoom.publicRoom(name, owner));
        }
        return chatRoomRepository.save(ChatRoom.privateRoom(name, owner, nextUnusedCode()));
    }

    /**
     * 저장 전에 중복을 미리 확인한다. 제약 위반을 잡아 같은 트랜잭션에서 재시도하면
     * 영속성 컨텍스트가 오염돼 다음 쿼리가 터진다.
     * ponytail: 사전 확인과 저장 사이 경합은 DB의 uk_chatrooms_invite_code가 최종 방어선이다.
     * 12자·32자 알파벳(약 60비트) 공간에서 확률이 무시할 수준이라 받아들인다.
     */
    private String nextUnusedCode() {
        for (int attempt = 0; attempt < CODE_RETRY; attempt++) {
            String code = inviteCodeGenerator.generate();
            if (!chatRoomRepository.existsByInviteCode(code)) {
                return code;
            }
        }
        throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Transactional
    public ChatRoom reissueInviteCode(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);
        if (!chatRoom.isPrivate()) {
            throw new CustomException(ErrorCode.INVALID_INVITE_CODE);
        }

        chatRoom.reissueInviteCode(nextUnusedCode());
        return chatRoom;
    }

    public ChatRoom getChatRoomById(Long chatRoomId){
        return chatRoomRepository.findByIdAndDeletedAtIsNull(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /**
     * 삭제된 방도 돌려준다. leave 전용이다 —
     * 삭제된 방을 못 찾으면 그 방의 멤버십 행을 사용자가 영영 지울 수 없다.
     */
    public ChatRoom getChatRoomByIdIncludingDeleted(Long chatRoomId){
        return chatRoomRepository.findById(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    public List<ChatRoom> getAllChatRooms(){
        return chatRoomRepository.findByDeletedAtIsNull();
    }

    @Transactional
    public void delete(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        // 커밋 후에는 조회하지 않는다. 회수 대상을 지금 걷어 이벤트에 싣는다.
        List<Long> memberIds = chatRoomMemberRepository.findMembersByChatRoomId(chatRoomId).stream()
                .map(Member::getId)
                .toList();

        chatRoom.softDelete();
        eventPublisher.publishEvent(new RoomDeletedEvent(chatRoomId, memberIds));
    }

    /** 주인이 없는 방(시드 방, 주인이 탈퇴한 방)은 아무도 운영할 수 없다. */
    private void requireOwner(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
    }
}
