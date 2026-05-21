package com.example.springboot_realtimechat.global.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // Member
    MEMBER_NOT_FOUND(404, "해당 회원을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(401, "비밀번호가 일치하지 않습니다."),

    // ChatRoom
    CHAT_ROOM_NOT_FOUND(404, "존재하지 않는 채팅방입니다."),

    // ChatRoomMember
    ALREADY_JOINED_ROOM(409, "이미 참여 중인 채팅방입니다."),
    NOT_JOINED_ROOM(403, "참여하지 않은 채팅방입니다."),

    // Message
    MESSAGE_NOT_FOUND(404, "해당 메시지를 찾을 수 없습니다."),
    NOT_MESSAGE_OWNER(403, "해당 메시지에 대한 권한이 없습니다."),

    // Global
    INVALID_INPUT_VALUE(400, "잘못된 입력값입니다."),
    DATA_INTEGRITY_VIOLATION(409, "데이터 무결성 제약 조건을 위반했습니다."),
    METHOD_NOT_ALLOWED(405, "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(500, "서버 내부 오류가 발생했습니다.");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }
}
