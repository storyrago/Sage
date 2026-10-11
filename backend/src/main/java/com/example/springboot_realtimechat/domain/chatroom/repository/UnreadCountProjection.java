package com.example.springboot_realtimechat.domain.chatroom.repository;

public interface UnreadCountProjection {
    Long getChatroomId();
    long getLastReadSeq();
    long getLastMessageSeq();
    long getReplyCount();
}
