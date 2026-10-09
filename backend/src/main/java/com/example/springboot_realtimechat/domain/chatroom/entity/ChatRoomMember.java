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

    @Column(name = "last_read_seq", nullable = false)
    private long lastReadSeq;

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

    /**
     * 새 멤버십의 시작 위치. 이미 저장된 행은 동시 요청에 안전한
     * ChatRoomMemberRepository.advanceLastRead로만 옮긴다.
     */
    public void startReadingAt(long seq, Long messageId) {
        this.lastReadSeq = seq;
        this.lastReadMessageId = messageId;
    }
}
