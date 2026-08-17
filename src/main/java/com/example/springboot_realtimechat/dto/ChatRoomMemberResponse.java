package com.example.springboot_realtimechat.dto;

import com.example.springboot_realtimechat.domain.ChatRoomMember;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRoomMemberResponse {
    private Long memberId;
    private String nickname;
    private String profileImageUrl;

    public ChatRoomMemberResponse(Long memberId, String nickname, String profileImageUrl) {
        this.memberId = memberId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
    }

    public static ChatRoomMemberResponse from(ChatRoomMember chatRoomMember){
        return new ChatRoomMemberResponse(
                chatRoomMember.getMember().getId(),
                chatRoomMember.getMember().getNickname(),
                chatRoomMember.getMember().getProfileImageUrl()
        );
    }
}
