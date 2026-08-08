package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.ChatRoomMember;
import com.example.springboot_realtimechat.dto.ChatRoomMemberResponse;
import com.example.springboot_realtimechat.dto.RoomJoinRequest;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/chatrooms/{chatroomId}/members")
public class ChatRoomMemberController {
        private final ChatRoomMemberService chatRoomMemberService;

        @PostMapping
        public ChatRoomMemberResponse join(
                        @PathVariable Long chatroomId,
                        @RequestBody(required = false) RoomJoinRequest request,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                String inviteCode = request == null ? null : request.getInviteCode();
                ChatRoomMember chatRoomMember = chatRoomMemberService.join(
                                customUserDetails.getMemberId(), chatroomId, inviteCode);
                return ChatRoomMemberResponse.from(chatRoomMember);
        }

        @GetMapping
        public List<ChatRoomMemberResponse> getAllChatRoomMembers(
                        @PathVariable Long chatroomId,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                List<ChatRoomMember> chatRoomMemberList =
                                chatRoomMemberService.getChatRoomMembersById(chatroomId, customUserDetails.getMemberId());
                return chatRoomMemberList.stream()
                                .map(ChatRoomMemberResponse::from)
                                .toList();
        }

        @DeleteMapping
        public ResponseEntity<Void> leave(
                        @PathVariable Long chatroomId,
                        @AuthenticationPrincipal CustomUserDetails customUserDetails) {
                chatRoomMemberService.leave(customUserDetails.getMemberId(), chatroomId);

                return ResponseEntity.noContent().build();
        }
}
