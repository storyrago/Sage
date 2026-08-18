package com.example.springboot_realtimechat.service;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomBan;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.BannedMemberResponse;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import com.example.springboot_realtimechat.event.RoomLeftEvent;
import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.repository.ChatRoomBanRepository;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.repository.MemberRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

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
    private final MemberRepository memberRepository;

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
        ChatRoomMember saved;
        try{
            // 트랜잭션이 끝나는 시점에 실제 SQL이 실행될 수 있어서, 중복 참여로 인한 unique 제약조건 예외가 try-catch 밖에서 발생할 수 있음.
            // saveAndFlush()는 저장한 뒤 즉시 DB에 반영을 시도.
            saved = chatRoomMemberRepository.saveAndFlush(chatRoomMember);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.ALREADY_JOINED_ROOM);
        }

        // 차단 검사와 저장 사이에 강퇴가 커밋됐을 수 있다. 다시 확인해 걸렸으면 트랜잭션을 되돌린다.
        if (chatRoomBanRepository.existsByChatRoomIdAndMemberId(chatRoomId, memberId)) {
            throw new CustomException(ErrorCode.ROOM_BANNED);
        }
        return saved;
    }

    // 코드가 없는 잠긴 방(레거시 동결 상태)은 어떤 입력으로도 열리지 않는다.
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
        // 잠긴 조회로 시작해 transferOwnership과의 경합에서 순서를 강제한다.
        ChatRoom chatRoom = chatRoomService.getChatRoomByIdIncludingDeletedForUpdate(chatRoomId);

        if (chatRoom.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        ChatRoomMember chatRoomMember = chatRoomMemberRepository
                .findByMemberAndChatRoom(member, chatRoom)
                        .orElseThrow(()->new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(chatRoomMember);
        eventPublisher.publishEvent(new RoomLeftEvent(memberId, chatRoomId, ErrorCode.ROOM_MEMBERSHIP_REVOKED));
    }

    /**
     * 멤버십 행만 지우면 강퇴가 무의미하다 — 프론트가 방 선택마다 join을 부르므로
     * 공개방은 우표 재클릭으로, 잠긴 방은 쓰던 코드로 즉시 복귀한다. 차단을 함께 남긴다.
     */
    @Transactional
    public void kick(Long chatRoomId, Long targetMemberId, Long requesterId) {
        // 잠긴 조회로 시작해 transferOwnership과의 경합에서 순서를 강제한다.
        ChatRoom chatRoom = chatRoomService.getChatRoomByIdForUpdate(chatRoomId);
        requireOwner(chatRoom, requesterId);
        if (chatRoom.isOwnedBy(targetMemberId)) {
            throw new CustomException(ErrorCode.OWNER_CANNOT_LEAVE);
        }

        Member target = memberService.getMemberById(targetMemberId);
        ChatRoomMember membership = chatRoomMemberRepository
                .findByMemberAndChatRoom(target, chatRoom)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_JOINED_ROOM));

        chatRoomMemberRepository.delete(membership);
        chatRoomBanRepository.save(new ChatRoomBan(chatRoomId, targetMemberId));
        eventPublisher.publishEvent(new RoomLeftEvent(targetMemberId, chatRoomId, ErrorCode.ROOM_KICKED));
    }

    @Transactional
    public void unban(Long chatRoomId, Long targetMemberId, Long requesterId) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);
        // 차단돼 있지 않아도 성공으로 둔다. 해제는 멱등이다.
        chatRoomBanRepository.deleteByChatRoomIdAndMemberId(chatRoomId, targetMemberId);
    }

    public List<BannedMemberResponse> getBannedMembers(Long chatRoomId, Long requesterId) {
        ChatRoom chatRoom = chatRoomService.getChatRoomById(chatRoomId);
        requireOwner(chatRoom, requesterId);

        List<ChatRoomBan> bans = chatRoomBanRepository.findByChatRoomId(chatRoomId);
        Map<Long, Member> members = memberRepository.findAllById(
                        bans.stream().map(ChatRoomBan::getMemberId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, m -> m));

        return bans.stream()
                .map(ban -> new BannedMemberResponse(
                        ban.getMemberId(),
                        members.containsKey(ban.getMemberId())
                                ? members.get(ban.getMemberId()).getNickname()
                                : null,
                        ban.getBannedAt()))
                .toList();
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
                .map(p -> new UnreadCountResponse(
                        p.getChatroomId(), p.getUnreadCount(), p.getReplyCount(), p.getLastReadMessageId()))
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

    /** 주인이 없는 방(시드/레거시 데이터)은 아무도 운영할 수 없다. */
    private void requireOwner(ChatRoom chatRoom, Long requesterId) {
        if (!chatRoom.isOwnedBy(requesterId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
    }
}
