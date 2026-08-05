package com.example.springboot_realtimechat.repository;


import com.example.springboot_realtimechat.domain.ChatRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
    List<ChatRoom> findByDeletedAtIsNull();

    Optional<ChatRoom> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByInviteCode(String inviteCode);
}
