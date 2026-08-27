package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               COUNT(m) AS unreadCount,
               COUNT(p) AS replyCount
        FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND (m.member IS NULL OR m.member <> cm.member)
           AND m.deletedAt IS NULL
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        LEFT JOIN Message p
            ON p.id = m.replyTo.id
           AND p.member = cm.member
        WHERE cm.member.id = :memberId
          AND r.deletedAt IS NULL
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);

    @Query("SELECT m FROM ChatRoomMember cm JOIN cm.member m WHERE cm.chatRoom.id = :roomId")
    List<Member> findMembersByChatRoomId(@Param("roomId") Long roomId);

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
