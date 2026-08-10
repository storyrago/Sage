package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import com.example.springboot_realtimechat.event.RoomLeftEvent;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MessageRepository;
import com.example.springboot_realtimechat.security.RoomAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomMemberService {
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;
    private final MessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final RoomAccess roomAccess;
    private final ChatRoomBanRepository chatRoomBanRepository;

    /**
     * 판정 순서가 중요하다. 차단이 가장 먼저다 —
     * 중복 참여 검사가 앞서면 차단된 기존 멤버가 ALREADY_JOINED로 통과해 보인다.
     */
    @Transactional
    public ChatRoomMember join(Long memberId, Long chatRoomId, String inviteCode){
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        if (chatRoomBanRepository.existsByChatRoomIdAndMemberId(chatRoomId, memberId)) {
            throw new CustomException(ErrorCode.ROOM_BANNED);
        }

        if (roomAccess.isMember(memberId, chatRoomId)) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

        if (chatRoom.isPrivate() && !matchesInviteCode(chatRoom, inviteCode)) {
            throw new CustomException(ErrorCode.INVALID_INVITE_CODE);
        }

        ChatRoomMember chatRoomMember = new ChatRoomMember(member, chatRoom);
        chatRoomMember.updateLastRead(messageRepository.findMaxIdByChatRoom(chatRoom));
        try{
            // 트랜잭션이 끝나는 시점에 실제 SQL이 실행될 수 있어서, 중복 참여로 인한 unique 제약조건 예외가 try-catch 밖에서 발생할 수 있음.
            // saveAndFlush()는 저장한 뒤 즉시 DB에 반영을 시도.
            return chatRoomMemberRepository.saveAndFlush(chatRoomMember);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

    }

    // 코드가 없는 잠긴 방(주인이 탈퇴한 동결 상태)은 어떤 입력으로도 열리지 않는다.
    private boolean matchesInviteCode(ChatRoom chatRoom, String inviteCode) {
        String actual = chatRoom.getInviteCode();
        if (actual == null || inviteCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                inviteCode.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public void leave(Long memberId, Long chatRoomId){
        Member member = memberService.getMemberById(memberId);
        // 삭제된 방도 조회한다. 못 찾으면 멤버십 행이 영영 남는다.
        ChatRoom chatRoom = chatRoomService.getChatRoomByIdIncludingDeleted(chatRoomId);

        if (chatRoom.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        ChatRoomMember chatRoomMember = chatRoomMemberRepository
                .findByMemberAndChatRoom(member, chatRoom)
                        .orElseThrow(()->new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(chatRoomMember);
        eventPublisher.publishEvent(new RoomLeftEvent(memberId, chatRoomId, ErrorCode.ROOM_MEMBERSHIP_REVOKED));
    }

    public List<ChatRoomMember> getChatRoomMembersById(Long chatRoomId, Long requesterId){
        if (!roomAccess.isMember(requesterId, chatRoomId)) {
            throw new CustomException(ErrorCode.NOT_JOINED_ROOM);
        }
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);

        return chatRoomMemberRepository.findByChatRoom(chatRoom);
    }

    public List<UnreadCountResponse> getUnreadCounts(Long memberId) {
        return chatRoomMemberRepository.findUnreadCountsByMemberId(memberId).stream()
                .map(p -> new UnreadCountResponse(p.getChatroomId(), p.getUnreadCount(), p.getLastReadMessageId()))
                .toList();
    }

    @Transactional
    public void markRead(Long memberId, Long chatRoomId) {
        Member member = memberService.getMemberById(memberId);
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        ChatRoomMember cm = chatRoomMemberRepository.findByMemberAndChatRoom(member, chatRoom)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_JOINED_ROOM));
        cm.updateLastRead(messageRepository.findMaxIdByChatRoom(chatRoom));
    }
}
