package com.example.springboot_realtimechat.repository;


import com.example.springboot_realtimechat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    List<ChatRoom> findByDeletedAtIsNull();

    Optional<ChatRoom> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByInviteCode(String inviteCode);

    /**
     * 주인을 지우고 초대 코드를 회수한다. 잠금은 유지한다 —
     * 코드만 지우면 잠긴 방이 공개방이 되어 아무나 들어온다.
     * 결과는 아무도 새로 들어올 수 없는 동결 상태다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.createdBy = null, r.inviteCode = null WHERE r.createdBy.id = :memberId")
    int releaseOwnedRooms(@Param("memberId") Long memberId);
}
