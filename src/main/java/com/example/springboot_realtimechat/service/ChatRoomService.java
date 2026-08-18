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
            throw new CustomException(ErrorCode.ROOM_NOT_LOCKED);
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

    /**
     * transferOwnership 전용. 방 행에 쓰기 잠금을 걸고 시작해
     * leave/kick과 경합에서 순서를 강제한다.
     */
    public ChatRoom getChatRoomByIdForUpdate(Long chatRoomId){
        return chatRoomRepository.findByIdAndDeletedAtIsNullForUpdate(chatRoomId)
                .orElseThrow(()->new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /** leave 전용 잠금 조회. 삭제된 방도 찾는 이유는 getChatRoomByIdIncludingDeleted와 같다. */
    public ChatRoom getChatRoomByIdIncludingDeletedForUpdate(Long chatRoomId){
        return chatRoomRepository.findByIdForUpdate(chatRoomId)
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

    @Transactional
    public ChatRoom setPrivate(Long chatRoomId, boolean isPrivate, Long requesterId) {
        ChatRoom chatRoom = getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        if (isPrivate) {
            // 전환할 때마다 새 코드를 뽑는다. 옛 코드가 부활하면 유출된 코드가 다시 유효해진다.
            chatRoom.makePrivate(nextUnusedCode());
        } else {
            chatRoom.makePublic();
        }
        return chatRoom;
    }

    @Transactional
    public ChatRoom transferOwnership(Long chatRoomId, Long newOwnerId, Long requesterId) {
        // 방 행을 먼저 잠근다 — leave/kick과의 경합(위임 확인 통과 후 대상이 나가거나
        // 강퇴당하는 인터리빙)을 막는 유일한 방법이다. chatrooms → chatroom_members
        // 순서를 leave/kick과 통일해 데드락을 피한다.
        ChatRoom chatRoom = getChatRoomByIdForUpdate(chatRoomId);
        requireOwner(chatRoom, requesterId);

        if (newOwnerId.equals(requesterId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        // 차단된 사람은 멤버가 아니므로 이 검사에서 함께 걸린다.
        if (!chatRoomMemberRepository.existsActiveMembership(newOwnerId, chatRoomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }

        Member newOwner = memberRepository.findById(newOwnerId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        chatRoom.transferOwnership(newOwner);
        // 잠긴 방은 코드도 함께 회전한다. setPrivate가 전환마다 코드를 새로 뽑는 것과
        // 같은 이유다 — 옛 코드가 살아있으면 방을 통제하지 못하게 된 옛 주인이
        // 여전히 유효한 입장 코드를 쥔다. 대가: 새 주인이 유지하고 싶었던 기존
        // 초대 링크도 함께 죽는다.
        if (chatRoom.isPrivate()) {
            chatRoom.reissueInviteCode(nextUnusedCode());
        }
        return chatRoom;
    }

    /**
     * 탈퇴하는 회원이 주인인 방들을 정리한다. 남은 멤버가 있으면 소유권을 넘기고,
     * 없으면 방을 닫는다. 주인 없는 방이 남으면 아무도 초대·강퇴·삭제를 할 수 없다.
     * 승계 대상은 가장 오래된 멤버십이다 — 참여 순서만 보면 되므로 추가 조회가 필요 없고
     * 같은 입력에 항상 같은 사람이 뽑힌다.
     * 방 하나라도 실패하면 탈퇴 전체가 되돌아간다(호출자 트랜잭션에 참여).
     */
    @Transactional
    public void succeedOwnedRooms(Long ownerId) {
        for (ChatRoom chatRoom : chatRoomRepository.findOwnedByMemberForUpdate(ownerId)) {
            Member successor = chatRoom.isDeleted() ? null
                    : chatRoomMemberRepository.findSuccessionCandidates(chatRoom.getId(), ownerId)
                            .stream().findFirst().orElse(null);

            if (successor == null) {
                chatRoom.softDelete();
                chatRoom.releaseOwnership();
                continue;
            }

            chatRoom.transferOwnership(successor);
            // transferOwnership 경로와 같은 규칙이다. 떠난 주인이 쥔 코드가 계속 유효하면 안 된다.
            if (chatRoom.isPrivate()) {
                chatRoom.reissueInviteCode(nextUnusedCode());
            }
        }
    }

    /** 주인이 없는 방(시드/레거시 데이터)은 아무도 운영할 수 없다. */
    private void requireOwner(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
    }
}
