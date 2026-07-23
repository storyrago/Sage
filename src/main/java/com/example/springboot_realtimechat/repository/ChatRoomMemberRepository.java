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
    boolean existsByMemberAndChatRoom(Member member, ChatRoom chatRoom);

    Optional<ChatRoomMember> findByMemberAndChatRoom(Member member, ChatRoom chatRoom);

    @Query("SELECT cm FROM ChatRoomMember cm JOIN FETCH cm.member WHERE cm.chatRoom = :room")
    List<ChatRoomMember> findByChatRoom(@Param("room") ChatRoom room);

    void deleteByMember(Member member);

    @Query("""
        SELECT cm.chatRoom.id AS chatroomId,
               cm.lastReadMessageId AS lastReadMessageId,
               COUNT(m) AS unreadCount
        FROM ChatRoomMember cm
        LEFT JOIN Message m
            ON m.chatRoom = cm.chatRoom
           AND m.member <> cm.member
           AND m.deleted = false
           AND (cm.lastReadMessageId IS NULL OR m.id > cm.lastReadMessageId)
        WHERE cm.member.id = :memberId
        GROUP BY cm.chatRoom.id, cm.lastReadMessageId
    """)
    List<UnreadCountProjection> findUnreadCountsByMemberId(@Param("memberId") Long memberId);
}
