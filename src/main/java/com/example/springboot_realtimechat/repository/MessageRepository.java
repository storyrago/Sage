package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    // 탈퇴해도 대화는 남긴다. 작성자 참조만 끊는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Message m SET m.member = null WHERE m.member = :member")
    int anonymizeByMember(@Param("member") Member member);

    boolean existsByImageUrl(String imageUrl);

    boolean existsByContentContaining(String url);

    // 최신 → 과거(id DESC). member는 fetch join으로 페이지 내 N+1 제거.
    // 작성자가 없는 메시지(탈퇴자)도 목록에 남아야 하므로 LEFT JOIN이다.
    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.member WHERE m.chatRoom = :room ORDER BY m.id DESC")
    List<Message> findLatestByChatRoom(@Param("room") ChatRoom room, Pageable pageable);

    @Query("SELECT m FROM Message m LEFT JOIN FETCH m.member WHERE m.chatRoom = :room AND m.id < :before ORDER BY m.id DESC")
    List<Message> findOlderByChatRoom(@Param("room") ChatRoom room, @Param("before") Long before, Pageable pageable);

    @Query("SELECT MAX(m.id) FROM Message m WHERE m.chatRoom = :room")
    Long findMaxIdByChatRoom(@Param("room") ChatRoom room);

    @Query("SELECT m.imageUrl FROM Message m WHERE m.member = :member AND m.imageUrl IS NOT NULL")
    List<String> findImageUrlsByMember(@Param("member") Member member);
}
