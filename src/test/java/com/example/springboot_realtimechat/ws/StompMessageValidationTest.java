package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.dto.MessageResponse;
import com.example.springboot_realtimechat.dto.WsErrorResponse;
import com.example.springboot_realtimechat.redis.RedisPublisher;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import com.example.springboot_realtimechat.service.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * STOMP에는 요청-응답이 없어 검증이 빠지면 실패가 아무 데도 도달하지 않는다.
 * 애노테이션 핸들러에 프레임을 그대로 태워, 제약이 실제로 돌고 사유가 개인 큐로 나가는지 확인한다.
 */
@SpringBootTest
@Transactional
class StompMessageValidationTest {

    @Autowired SimpAnnotationMethodMessageHandler annotationMethodMessageHandler;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    @MockitoSpyBean SimpMessagingTemplate messagingTemplate;
    @MockitoBean RedisPublisher redisPublisher;
    @MockitoBean S3Service s3Service;

    private Member sender;
    private Long roomId;
    private String destination;

    @BeforeEach
    void setUp() {
        sender = memberService.create("stomp-validation@test.com", "1234", "보내는사람");
        ChatRoom room = chatRoomService.create("검증방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(sender.getId(), roomId, null);
        destination = "/pub/chatrooms/" + roomId + "/messages";
        when(s3Service.presignedGetUrl(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(s3Service.extractKey(any())).thenReturn(null); // 외부 URL로 취급해 소유자 검사를 통과시킨다
    }

    /** 등록된 애노테이션 핸들러가 실제로 라우팅·검증·예외 처리까지 수행한다. */
    private void send(String json) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setDestination(destination);
        accessor.setSessionId("test-session");
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        CustomUserDetails details = new CustomUserDetails(sender.getId(), sender.getEmail());
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        accessor.setUser(authentication);
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder
                .createMessage(json.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
        annotationMethodMessageHandler.handleMessage(message);
    }

    private WsErrorResponse capturedError() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate)
                .convertAndSendToUser(eq(String.valueOf(sender.getId())), eq("/queue/errors"), payload.capture());
        return (WsErrorResponse) payload.getValue();
    }

    @Test
    void 길이를_넘긴_메시지는_저장되지_않고_개인_큐로_사유가_간다() {
        send("{\"content\":\"" + "가".repeat(501) + "\"}");

        verify(redisPublisher, never()).publish(any(MessageResponse.class));

        WsErrorResponse sent = capturedError();
        assertThat(sent.code()).isEqualTo("INVALID_INPUT_VALUE");
        assertThat(sent.message()).contains("500자");
        assertThat(sent.destination()).isEqualTo(destination);
    }

    @Test
    void 최대_길이_메시지는_통과한다() {
        send("{\"content\":\"" + "가".repeat(500) + "\"}");

        verify(redisPublisher).publish(any(MessageResponse.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    void 이미지만_있는_메시지는_통과한다() {
        send("{\"content\":\"\",\"imageUrl\":\"https://cdn.example.com/photo.png\"}");

        verify(redisPublisher).publish(any(MessageResponse.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }
}
