package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    void deleteByMember(Member member);

    // 최신 → 과거(id DESC). member는 fetch join으로 페이지 내 N+1 제거.
    @Query("SELECT m FROM Message m JOIN FETCH m.member WHERE m.chatRoom = :room ORDER BY m.id DESC")
    List<Message> findLatestByChatRoom(@Param("room") ChatRoom room, Pageable pageable);

    @Query("SELECT m FROM Message m JOIN FETCH m.member WHERE m.chatRoom = :room AND m.id < :before ORDER BY m.id DESC")
    List<Message> findOlderByChatRoom(@Param("room") ChatRoom room, @Param("before") Long before, Pageable pageable);

    @Query("SELECT MAX(m.id) FROM Message m WHERE m.chatRoom = :room")
    Long findMaxIdByChatRoom(@Param("room") ChatRoom room);
}
