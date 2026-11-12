package com.example.springboot_realtimechat.application.account;

import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 절차. 회원 도메인은 채팅방·메시지를 모르므로, 그 정리를 여기서 각 도메인에 순서대로 시킨다.
 * 한 트랜잭션이다 — 하나라도 실패하면 탈퇴 전체가 되돌아간다.
 */
@Service
@RequiredArgsConstructor
public class AccountWithdrawalService {
    private final MemberService memberService;
    private final ChatRoomService chatRoomService;
    private final ChatRoomMemberService chatRoomMemberService;
    private final MessageService messageService;

    @Transactional
    public void withdraw(Long memberId) {
        Member member = memberService.getMemberById(memberId);

        // 방 승계는 남은 멤버십에서 후계자를 고르므로 멤버십 삭제보다 먼저다.
        chatRoomService.succeedOwnedRooms(memberId);
        chatRoomMemberService.removeAllOf(member);
        messageService.anonymizeAuthor(member);
        memberService.deleteRecord(memberId);
    }
}
