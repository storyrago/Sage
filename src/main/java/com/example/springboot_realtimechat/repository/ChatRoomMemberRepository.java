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
}
