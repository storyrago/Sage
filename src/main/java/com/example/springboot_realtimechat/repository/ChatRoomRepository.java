package com.example.springboot_realtimechat.repository;


import com.example.springboot_realtimechat.domain.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * 위임·나가기·강퇴가 같은 방 행을 두고 경합한다(transferOwnership ↔ leave/kick).
     * 비잠금 재확인은 REPEATABLE READ 스냅샷에 막혀 못 잡는다 — 잠긴 조회만
     * 최신 커밋을 강제로 읽는다. 세 경로 모두 이 조회로 시작해야 순서가 보장된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ChatRoom r WHERE r.id = :id AND r.deletedAt IS NULL")
    Optional<ChatRoom> findByIdAndDeletedAtIsNullForUpdate(@Param("id") Long id);

    /** leave 전용 잠금 조회 — 삭제된 방도 잠가서 반환한다. 못 찾으면 멤버십 행이 영영 남는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ChatRoom r WHERE r.id = :id")
    Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

    /**
     * 주인을 지우고 초대 코드를 회수한다. 잠금은 유지한다 —
     * 코드만 지우면 잠긴 방이 공개방이 되어 아무나 들어온다.
     * 결과는 아무도 새로 들어올 수 없는 동결 상태다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChatRoom r SET r.createdBy = null, r.inviteCode = null WHERE r.createdBy.id = :memberId")
    int releaseOwnedRooms(@Param("memberId") Long memberId);
}
