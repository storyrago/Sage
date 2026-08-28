package com.example.springboot_realtimechat.domain.chatroom.entity;

import com.example.springboot_realtimechat.domain.member.entity.Member;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
@Table(name="chatroom_members",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "chatroom_id"})
    })
public class ChatRoomMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chatroom_id")
    private ChatRoom chatRoom;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    public ChatRoomMember(Member member, ChatRoom chatRoom) {
        connect(member, chatRoom);
    }

    private void connect(Member member, ChatRoom chatRoom){
        this.member = member;
        this.chatRoom = chatRoom;

        member.getChatRoomMembers().add(this);
        chatRoom.getChatRoomMembers().add(this);
    }

    public void updateLastRead(Long messageId) {
        this.lastReadMessageId = messageId;
    }
}
