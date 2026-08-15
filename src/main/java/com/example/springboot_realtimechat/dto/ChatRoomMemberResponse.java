package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.ChatRoomMember;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomMemberResponse {
    private Long id;
    private Long memberId;
    private Long chatRoomId;
    private String nickname;
    private String profileImageUrl;

    public ChatRoomMemberResponse(Long id, Long memberId, Long chatRoomId, String nickname, String profileImageUrl) {
        this.id = id;
        this.memberId = memberId;
        this.chatRoomId = chatRoomId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public static ChatRoomMemberResponse from(ChatRoomMember chatRoomMember){
        return new ChatRoomMemberResponse(
                chatRoomMember.getId(),
                chatRoomMember.getMember().getId(),
                chatRoomMember.getChatRoom().getId(),
                chatRoomMember.getMember().getNickname(),
                chatRoomMember.getMember().getProfileImageUrl()
        );
    }
}
