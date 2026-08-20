package com.example.springboot_realtimechat.repository;


import com.example.springboot_realtimechat.domain.ChatRoom;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * 탈퇴 처리 전용. 그 회원이 주인인 방을 전부 잠가서 가져온다.
     * 위임·나가기·강퇴와 같은 chatrooms → chatroom_members 순서를 지키기 위한 시작점이다.
     * 삭제된 방도 포함한다 — created_by가 남아 있으면 회원 행을 지울 수 없다.
     * id 순으로 잠가 동시 탈퇴끼리도 순서가 엇갈리지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ChatRoom r WHERE r.createdBy.id = :memberId ORDER BY r.id")
    List<ChatRoom> findOwnedByMemberForUpdate(@Param("memberId") Long memberId);
}
