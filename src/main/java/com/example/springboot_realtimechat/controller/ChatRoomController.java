package com.example.springboot_realtimechat.controller;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.dto.BannedMemberResponse;
import com.example.springboot_realtimechat.dto.ChatRoomRequest;
import com.example.springboot_realtimechat.dto.ChatRoomResponse;
import com.example.springboot_realtimechat.dto.UnreadCountResponse;
import com.example.springboot_realtimechat.repository.ChatRoomMemberRepository;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chatrooms")
public class ChatRoomController {
    private final ChatRoomService chatRoomService;
    private final ChatRoomMemberService chatRoomMemberService;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    @PostMapping
    public ChatRoomResponse create(@Valid @RequestBody ChatRoomRequest chatRoomRequest,
                                   @AuthenticationPrincipal CustomUserDetails user){
        ChatRoom chatRoom = chatRoomService.create(
                chatRoomRequest.getName(), chatRoomRequest.isPrivate(), user.getMemberId());
        return ChatRoomResponse.from(chatRoom, user.getMemberId(), true);
    }

    @GetMapping("/unread")
    public List<UnreadCountResponse> getUnreadCounts(@AuthenticationPrincipal CustomUserDetails user) {
        return chatRoomMemberService.getUnreadCounts(user.getMemberId());
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomMemberService.markRead(user.getMemberId(), id);
    }

    @GetMapping
    public List<ChatRoomResponse> getChatRooms(@AuthenticationPrincipal CustomUserDetails user){
        Long requesterId = user.getMemberId();
        // 방마다 멤버십을 조회하면 방 수만큼 쿼리가 돈다. 한 번에 걷어 메모리에서 대조한다.
        Set<Long> joinedRoomIds = new HashSet<>(
                chatRoomMemberRepository.findChatRoomIdsByMemberId(requesterId));

        return chatRoomService.getAllChatRooms().stream()
                .map(room -> ChatRoomResponse.from(room, requesterId, joinedRoomIds.contains(room.getId())))
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomService.delete(id, user.getMemberId());
    }

    @GetMapping("/{id}/bans")
    public List<BannedMemberResponse> getBannedMembers(@PathVariable Long id,
                                                        @AuthenticationPrincipal CustomUserDetails user) {
        return chatRoomMemberService.getBannedMembers(id, user.getMemberId());
    }

    @DeleteMapping("/{id}/bans/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unban(@PathVariable Long id, @PathVariable Long memberId,
                      @AuthenticationPrincipal CustomUserDetails user) {
        chatRoomMemberService.unban(id, memberId, user.getMemberId());
    }
}
