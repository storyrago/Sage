package com.example.springboot_realtimechat.event;

import java.util.List;

/**
 * 회수 대상을 페이로드에 싣는다. AFTER_COMMIT 시점에 다시 조회하지 않기 위해서다.
 */
public record RoomDeletedEvent(Long roomId, List<Long> memberIds) {
}
