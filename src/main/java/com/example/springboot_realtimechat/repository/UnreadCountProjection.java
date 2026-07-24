package com.example.springboot_realtimechat.repository;

public interface UnreadCountProjection {
    Long getChatroomId();
    Long getLastReadMessageId();
    long getUnreadCount();
}
