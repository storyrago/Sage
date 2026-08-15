package com.example.springboot_realtimechat.exception;

import com.example.springboot_realtimechat.global.exception.CustomException;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.global.exception.ErrorResponse;
import com.example.springboot_realtimechat.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// 프론트가 오류 사유를 문자열이 아니라 코드로 구분할 수 있어야 한다.
// 문구가 바뀌어도 분기가 깨지지 않는 것이 목적이다.
class ErrorResponseCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 오류_응답에_ErrorCode_이름이_실린다() {
        ResponseEntity<ErrorResponse> response =
                handler.handleCustomException(new CustomException(ErrorCode.ALREADY_JOINED_ROOM));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("ALREADY_JOINED_ROOM");
        assertThat(response.getBody().getMessage()).isEqualTo("이미 참여 중인 채팅방입니다.");
    }

    @Test
    void 서로_다른_오류는_서로_다른_코드를_갖는다() {
        String joined = handler.handleCustomException(
                new CustomException(ErrorCode.ALREADY_JOINED_ROOM)).getBody().getCode();
        String notJoined = handler.handleCustomException(
                new CustomException(ErrorCode.NOT_JOINED_ROOM)).getBody().getCode();

        assertThat(joined).isNotEqualTo(notJoined);
    }
}
