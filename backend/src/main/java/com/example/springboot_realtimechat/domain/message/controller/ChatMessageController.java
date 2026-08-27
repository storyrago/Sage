package com.example.springboot_realtimechat.domain.message.controller;

import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.dto.MessageRequest;
import com.example.springboot_realtimechat.domain.message.dto.MessageResponse;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.service.MessageResponseFactory;
import com.example.springboot_realtimechat.domain.message.service.MessageService;
import com.example.springboot_realtimechat.domain.presence.dto.TypingRequest;
import com.example.springboot_realtimechat.domain.presence.dto.TypingResponse;
import com.example.springboot_realtimechat.global.auth.CustomUserDetails;
import com.example.springboot_realtimechat.global.exception.ErrorCode;
import com.example.springboot_realtimechat.global.redis.RedisPublisher;
import com.example.springboot_realtimechat.global.websocket.WsErrorResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.security.Principal;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageController {
    private final MessageService messageService;
    private final MemberService memberService;
    private final RedisPublisher redisPublisher;
    private final MessageResponseFactory messageResponseFactory;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chatrooms/{chatroomId}/messages")
    public void sendMessage(
            @DestinationVariable Long chatroomId,
            @Valid MessageRequest messageRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();

        Message message = messageService.create(
                messageRequest.getContent(),
                messageRequest.getImageUrl(),
                customUserDetails.getMemberId(),
                chatroomId,
                messageRequest.getReplyToId());
        MessageResponse messageResponse = messageResponseFactory.of(message);
        redisPublisher.publish(messageResponse);
    }

    @MessageMapping("/chatrooms/{chatroomId}/typing")
    public void typing(
            @DestinationVariable Long chatroomId,
            TypingRequest typingRequest,
            Principal principal) {
        CustomUserDetails customUserDetails = (CustomUserDetails) ((Authentication) principal).getPrincipal();
        Member member = memberService.getMemberById(customUserDetails.getMemberId());
        TypingResponse response = new TypingResponse(
                chatroomId, member.getId(), member.getNickname(), typingRequest.isTyping());
        redisPublisher.publishTyping(response);
    }

    /**
     * STOMP에는 요청-응답이 없어 검증 실패가 보낸 사람에게 돌아가지 않는다.
     * 세션은 그대로 두고 개인 채널로만 사유를 보내, 메시지가 사라진 것처럼 보이지 않게 한다.
     */
    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    public void handleInvalidPayload(
            MethodArgumentNotValidException exception,
            Principal principal,
            @Header(name = "simpDestination", required = false) String destination) {
        log.warn("STOMP 페이로드 검증 실패: destination={}, principal={}", destination, principal.getName());
        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/errors",
                new WsErrorResponse(
                        ErrorCode.INVALID_INPUT_VALUE.name(),
                        reasonOf(exception),
                        destination));
    }

    /** 어긋난 제약의 메시지를 그대로 쓴다. 없으면 일반 문구로 떨어진다. */
    private String reasonOf(MethodArgumentNotValidException exception) {
        BindingResult bindingResult = exception.getBindingResult();
        FieldError fieldError = bindingResult != null ? bindingResult.getFieldError() : null;
        String message = fieldError != null ? fieldError.getDefaultMessage() : null;
        return message != null ? message : ErrorCode.INVALID_INPUT_VALUE.getMessage();
    }
}
