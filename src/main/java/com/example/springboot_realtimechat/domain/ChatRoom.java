package com.example.springboot_realtimechat.domain;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@Table(name="chatrooms")
public class ChatRoom {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length=100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    // 주인 없는 방은 시드/레거시 데이터뿐이다. 살아있는 방은 항상 주인이 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Member owner;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "invite_code", length = 12, unique = true)
    private String inviteCode;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "chatRoom")
    private List<ChatRoomMember> chatRoomMembers = new ArrayList<>();

    @OneToMany(mappedBy = "chatRoom")
    private List<Message> messages = new ArrayList<>();

    private ChatRoom(String name, Member owner, boolean isPrivate, String inviteCode) {
        this.name = name;
        this.owner = owner;
        this.isPrivate = isPrivate;
        this.inviteCode = inviteCode;
    }

    public static ChatRoom publicRoom(String name, Member owner) {
        return new ChatRoom(name, owner, false, null);
    }

    public static ChatRoom privateRoom(String name, Member owner, String inviteCode) {
        return new ChatRoom(name, owner, true, inviteCode);
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public boolean isOwnedBy(Long memberId) {
        return owner != null
                && owner.getId() != null
                && owner.getId().equals(memberId);
    }

    /** 주인이 없는 방(시드·레거시 데이터)은 아무도 운영할 수 없다. 주인이 탈퇴하면 소유권이 승계된다. */
    public void requireOwnedBy(Long memberId) {
        if (!isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.NOT_ROOM_OWNER);
        }
    }

    /** 이미 삭제된 방을 다시 삭제해도 최초 삭제 시각을 유지한다. */
    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = LocalDateTime.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 잠금과 코드는 항상 함께 움직인다. 코드만 지우면 잠긴 방이 공개방이 된다. */
    public void makePublic() {
        this.isPrivate = false;
        this.inviteCode = null;
    }

    public void makePrivate(String inviteCode) {
        this.isPrivate = true;
        this.inviteCode = inviteCode;
    }

    public void reissueInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    /**
     * 주인만 바꾼다. 잠긴 방의 코드 회전은 이 메서드가 아니라
     * ChatRoomService.transferOwnership이 조건부로 처리한다.
     */
    public void transferOwnership(Member newOwner) {
        this.owner = newOwner;
    }

    /** 승계받을 사람이 없어 닫는 방에 쓴다. 남은 코드는 회수한다. */
    public void releaseOwnership() {
        this.owner = null;
        this.inviteCode = null;
    }
}
