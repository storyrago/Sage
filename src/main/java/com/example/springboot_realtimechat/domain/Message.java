package com.example.springboot_realtimechat.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "messages")
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id")
    private ChatRoom chatRoom;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reply_to_id")
    private Message replyTo;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(nullable = false)
    private boolean deleted = false;

    public Message(String content, String imageUrl, Member member, ChatRoom chatRoom, Message replyTo) {
        this.content = content;
        this.imageUrl = imageUrl;
        this.replyTo = replyTo;
        connect(member, chatRoom);
    }

    private void connect(Member member, ChatRoom chatRoom) {
        this.member = member;
        this.chatRoom = chatRoom;

        // 동기화
        member.getMessages().add(this);
        chatRoom.getMessages().add(this);
    }

    public void edit(String content) {
        this.content = content;
        this.editedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deleted = true;
        this.content = "";
        this.imageUrl = null;
    }
}
