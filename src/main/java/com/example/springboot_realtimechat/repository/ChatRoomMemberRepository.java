package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {
    boolean existsByMemberAndChatRoom(Member member, ChatRoom chatRoom);

    Optional<ChatRoomMember> findByMemberAndChatRoom(Member member, ChatRoom chatRoom);

    List<ChatRoomMember> findByChatRoom(ChatRoom chatRoom);

    void deleteByMember(Member member);
}
