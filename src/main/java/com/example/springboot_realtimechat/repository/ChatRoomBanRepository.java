package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoomBan;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomBanRepository extends JpaRepository<ChatRoomBan, ChatRoomBan.Id> {
    boolean existsByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);

    void deleteByMemberId(Long memberId);
}
