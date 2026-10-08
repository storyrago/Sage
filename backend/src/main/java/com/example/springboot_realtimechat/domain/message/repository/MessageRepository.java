package com.example.springboot_realtimechat.domain.message.repository;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.message.entity.Message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

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

    /** 부모 메시지 작성자 판정용. FK 컬럼만 읽으므로 조인이 생기지 않는다. 탈퇴자·없는 메시지는 null. */
    @Query("SELECT m.member.id FROM Message m WHERE m.id = :messageId")
    Long findAuthorIdById(@Param("messageId") Long messageId);

    /**
     * 재전송 확인 전용. 방 잠금을 얻은 뒤 호출한다.
     * 일반 조회는 REPEATABLE READ 스냅샷에 막혀 잠금을 기다리는 동안 먼저 커밋된 행을 못 본다 —
     * 잠금 조회만 최신 커밋을 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Message m WHERE m.member.id = :memberId AND m.clientMessageId = :clientMessageId")
    Optional<Message> findByMemberIdAndClientMessageIdForUpdate(@Param("memberId") Long memberId,
                                                               @Param("clientMessageId") String clientMessageId);
}
