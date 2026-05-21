package com.example.springboot_realtimechat.global.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e){
        ErrorCode errorCode = e.getErrorCode();

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(new ErrorResponse(errorCode.getMessage()));
    }

    //스프링이 자동으로 던지는 예외
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e){
        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(new ErrorResponse(errorCode.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e){
        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(new ErrorResponse(errorCode.getMessage()));
    }

    //db/jpa가 던지는 예외
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e){
        ErrorCode errorCode = ErrorCode.DATA_INTEGRITY_VIOLATION;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(new ErrorResponse(errorCode.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception occurred", e);
        return ResponseEntity
                .status(500)
                .body(new ErrorResponse("서버 내부 오류가 발생했습니다."));
    }
}
