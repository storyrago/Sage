package com.example.springboot_realtimechat.domain.chatroom.repository;

public interface UnreadCountProjection {
    Long getChatroomId();
    Long getLastReadMessageId();
    long getLastReadSeq();
    long getLastMessageSeq();
    long getReplyCount();
}
