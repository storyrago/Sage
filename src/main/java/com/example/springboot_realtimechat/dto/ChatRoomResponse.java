package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ChatRoomResponse {
    private final Long id;
    private final String name;
    private final LocalDateTime createdAt;
    private final boolean locked;
    private final boolean joined;
    private final boolean owner;

    // 주인이 아니면 필드 자체를 응답에서 뺀다.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String inviteCode;

    private ChatRoomResponse(Long id, String name, LocalDateTime createdAt,
                             boolean locked, boolean joined, boolean owner, String inviteCode) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.locked = locked;
        this.joined = joined;
        this.owner = owner;
        this.inviteCode = inviteCode;
    }

    /**
     * 요청자를 반드시 받는다. 요청자 없는 팩토리를 두면 초대 코드가 전원에게 나간다.
     */
    public static ChatRoomResponse from(ChatRoom chatRoom, Long requesterId, boolean joined){
        boolean owner = chatRoom.isOwnedBy(requesterId);

        return new ChatRoomResponse(
                chatRoom.getId(),
                chatRoom.getName(),
                chatRoom.getCreatedAt(),
                chatRoom.isPrivate(),
                joined,
                owner,
                owner ? chatRoom.getInviteCode() : null
        );
    }
}
