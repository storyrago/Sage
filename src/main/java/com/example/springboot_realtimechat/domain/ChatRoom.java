package com.example.springboot_realtimechat.domain;

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

    // 주인 없는 방이 정상 상태다. 시드 방과 주인이 탈퇴한 방이 여기 해당한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private Member createdBy;

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

    private ChatRoom(String name, Member createdBy, boolean isPrivate, String inviteCode) {
        this.name = name;
        this.createdBy = createdBy;
        this.isPrivate = isPrivate;
        this.inviteCode = inviteCode;
    }

    public static ChatRoom publicRoom(String name, Member createdBy) {
        return new ChatRoom(name, createdBy, false, null);
    }

    public static ChatRoom privateRoom(String name, Member createdBy, String inviteCode) {
        return new ChatRoom(name, createdBy, true, inviteCode);
    }

    public boolean isPrivate() {
        return isPrivate;
    }

    public boolean isOwnedBy(Long memberId) {
        return createdBy != null
                && createdBy.getId() != null
                && createdBy.getId().equals(memberId);
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
}
