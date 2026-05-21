package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByChatRoomOrderById(ChatRoom chatRoom);

    void deleteByMember(Member member);
}
