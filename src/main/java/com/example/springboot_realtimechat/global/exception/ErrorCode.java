package com.example.springboot_realtimechat.global.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    // Member
    MEMBER_NOT_FOUND(404, "해당 회원을 찾을 수 없습니다."),
    DUPLICATE_EMAIL(409, "이미 사용 중인 이메일입니다."),
    INVALID_PASSWORD(401, "비밀번호가 일치하지 않습니다."),
    TOO_MANY_LOGIN_ATTEMPTS(429, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    EMAIL_ALREADY_REGISTERED(409, "이미 등록된 이메일입니다. 기존에 사용하던 소셜 계정으로 로그인해 주세요."),
    INVALID_NICKNAME(400, "닉네임은 공백을 제외하고 1자 이상 20자 이하여야 합니다."),
    SOCIAL_LOGIN_ONLY(401, "소셜 로그인으로 가입된 계정입니다. 소셜 로그인을 이용해 주세요."),

    // ChatRoom
    CHAT_ROOM_NOT_FOUND(404, "존재하지 않는 채팅방입니다."),
    NOT_ROOM_OWNER(403, "방장만 할 수 있어요."),
    ROOM_DELETED(403, "방이 삭제되었어요."),
    ROOM_KICKED(403, "방에서 내보내졌어요."),
    ROOM_NOT_LOCKED(409, "잠긴 방에서만 초대 코드를 발급할 수 있어요."),

    // ChatRoomMember
    ALREADY_JOINED_ROOM(409, "이미 참여 중인 채팅방입니다."),
    NOT_JOINED_ROOM(403, "참여하지 않은 채팅방입니다."),
    ROOM_MEMBERSHIP_REVOKED(403, "채팅방에서 나갔어요."),
    INVALID_INVITE_CODE(403, "초대 코드가 올바르지 않습니다."),
    ROOM_BANNED(403, "이 채팅방에 참여할 수 없습니다."),
    OWNER_CANNOT_LEAVE(409, "방장은 방을 나갈 수 없습니다. 방을 삭제해 주세요."),

    // Message
    MESSAGE_NOT_FOUND(404, "해당 메시지를 찾을 수 없습니다."),
    NOT_MESSAGE_OWNER(403, "해당 메시지에 대한 권한이 없습니다."),
    EMPTY_MESSAGE(400, "내용 또는 이미지가 필요합니다."),

    // Global
    UNAUTHORIZED(401, "세션이 만료되었어요. 다시 로그인해 주세요."),
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
