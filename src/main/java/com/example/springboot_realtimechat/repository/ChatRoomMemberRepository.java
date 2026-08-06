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
               COUNT(m) AS unreadCount
        FROM ChatRoomMember cm
        JOIN cm.chatRoom r
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND (m.member IS NULL OR m.member <> cm.member)
           AND m.deleted = false
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        WHERE cm.member.id = :memberId
          AND r.deletedAt IS NULL
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);

    @Query("SELECT m FROM ChatRoomMember cm JOIN cm.member m WHERE cm.chatRoom.id = :roomId")
    List<Member> findMembersByChatRoomId(@Param("roomId") Long roomId);

    @Query("SELECT cm.chatRoom.id FROM ChatRoomMember cm WHERE cm.member.id = :memberId")
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
