package com.example.springboot_realtimechat.domain.chatroom.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "chatroom_bans")
@IdClass(ChatRoomBan.Id.class)
public class ChatRoomBan {

    @jakarta.persistence.Id
    @Column(name = "chatroom_id")
    private Long chatRoomId;

    @jakarta.persistence.Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "banned_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime bannedAt;

    public ChatRoomBan(Long chatRoomId, Long memberId) {
        this.chatRoomId = chatRoomId;
        this.memberId = memberId;
    }

    @lombok.Getter
    @NoArgsConstructor
    public static class Id implements Serializable {
        private Long chatRoomId;
        private Long memberId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(chatRoomId, other.chatRoomId)
                    && Objects.equals(memberId, other.memberId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chatRoomId, memberId);
        }
    }
}
