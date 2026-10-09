package com.example.springboot_realtimechat.domain.chatroom.repository;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoomMember;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.entity.Message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {
    boolean existsByMemberIdAndChatRoomId(Long memberId, Long chatRoomId);

    Optional<ChatRoomMember> findByMemberAndChatRoom(Member member, ChatRoom chatRoom);

    @Query("SELECT cm FROM ChatRoomMember cm JOIN FETCH cm.member WHERE cm.chatRoom = :room")
    List<ChatRoomMember> findByChatRoom(@Param("room") ChatRoom room);

    void deleteByMember(Member member);

    /**
     * 읽음 위치를 앞으로만 옮긴다. 조건부 UPDATE라 동시에 들어온 요청이나 늦게 도착한 옛 요청이
     * 위치를 되돌리지 못한다. 옮겼으면 1, 이미 그 이후를 읽었거나 멤버가 아니면 0.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
        UPDATE ChatRoomMember cm
        SET cm.lastReadSeq = :seq, cm.lastReadMessageId = :messageId
        WHERE cm.member.id = :memberId
          AND cm.chatRoom.id = :chatRoomId
          AND cm.lastReadSeq < :seq
    """)
    int advanceLastRead(@Param("memberId") Long memberId,
                        @Param("chatRoomId") Long chatRoomId,
                        @Param("seq") long seq,
                        @Param("messageId") Long messageId);

    // 안읽음 수는 방 최신 순번 − 읽은 순번으로 서비스가 계산한다. 여기서는 답장 수만 센다.
    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               cm.lastReadSeq AS lastReadSeq,
               r.lastMessageSeq AS lastMessageSeq,
               COUNT(p) AS replyCount
        FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        LEFT JOIN Message m
            ON m.chatRoom = r
           AND m.seq > cm.lastReadSeq
           AND m.deletedAt IS NULL
           AND (m.member IS NULL OR m.member <> cm.member)
        LEFT JOIN Message p
            ON p.id = m.replyTo.id
           AND p.member = cm.member
        WHERE cm.member.id = :memberId
          AND r.deletedAt IS NULL
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId, cm.lastReadSeq, r.lastMessageSeq
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);

    @Query("SELECT m FROM ChatRoomMember cm JOIN cm.member m WHERE cm.chatRoom.id = :roomId")
    List<Member> findMembersByChatRoomId(@Param("roomId") Long roomId);

    /** 안읽음 통지 대상 판정용. 회원 엔티티를 읽지 않고 id만 가져온다. */
    @Query("SELECT cm.member.id FROM ChatRoomMember cm WHERE cm.chatRoom.id = :roomId")
    List<Long> findMemberIdsByChatRoomId(@Param("roomId") Long roomId);

    /**
     * 소유권 승계 후보. 멤버십 id가 곧 참여 순서라 가장 오래된 멤버가 앞에 온다.
     * 탈퇴하는 주인은 아직 멤버십 행이 남아 있으므로 제외한다.
     */
    @Query("""
        SELECT cm.member FROM ChatRoomMember cm
        WHERE cm.chatRoom.id = :chatRoomId
          AND cm.member.id <> :excludedMemberId
        ORDER BY cm.id
    """)
    List<Member> findSuccessionCandidates(@Param("chatRoomId") Long chatRoomId,
                                          @Param("excludedMemberId") Long excludedMemberId);

    @Query("SELECT cm.chatRoom.id FROM ChatRoomMember cm JOIN cm.chatRoom r WHERE cm.member.id = :memberId AND r.deletedAt IS NULL")
    List<Long> findChatRoomIdsByMemberId(@Param("memberId") Long memberId);

    @Query("""
        SELECT COUNT(cm) > 0 FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        WHERE cm.member.id = :memberId
          AND r.id = :chatRoomId
          AND r.deletedAt IS NULL
    """)
    boolean existsActiveMembership(@Param("memberId") Long memberId,
                                   @Param("chatRoomId") Long chatRoomId);
}
