package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.ChatRoomBan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatRoomBanRepository extends JpaRepository<ChatRoomBan, ChatRoomBan.Id> {
    boolean existsByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);

    void deleteByMemberId(Long memberId);

    void deleteByChatRoomIdAndMemberId(Long chatRoomId, Long memberId);

    List<ChatRoomBan> findByChatRoomId(Long chatRoomId);
}
