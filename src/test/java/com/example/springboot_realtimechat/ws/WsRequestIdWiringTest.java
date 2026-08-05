package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.domain.ChatRoom;
import com.example.springboot_realtimechat.domain.Member;
import com.example.springboot_realtimechat.logging.RequestIdFilter;
import com.example.springboot_realtimechat.logging.WsRequestIdChannelInterceptor;
import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.JwtAuthChannelInterceptor;
import com.example.springboot_realtimechat.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.service.ChatRoomService;
import com.example.springboot_realtimechat.service.MemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인터셉터 단위 테스트는 직접 new 해서 부르므로, 실제 스프링 배선(등록 순서·핸들러 스레드 전파)이
 * 어긋나도 잡아내지 못한다. clientInboundChannel에 실제로 메시지를 보내 그 배선을 검증한다.
 */
@SpringBootTest
@Transactional
class WsRequestIdWiringTest {

    @Autowired
    @Qualifier("clientInboundChannel")
    AbstractSubscribableChannel clientInboundChannel;

    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;

    private Member member;
    private Long roomId;

    @BeforeEach
    void setUp() {
        member = memberService.create("member@test.com", "1234", "멤버");
        ChatRoom room = chatRoomService.create("방", false, null);
        roomId = room.getId();
        chatRoomMemberService.join(member.getId(), roomId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** 등록된 인터셉터를 실제 순서대로 통과시킨다. 어느 하나가 프레임을 버리면 null이 나온다. */
    private Message<?> through(Message<?> message) {
        for (ChannelInterceptor interceptor : clientInboundChannel.getInterceptors()) {
            message = interceptor.preSend(message, clientInboundChannel);
            if (message == null) {
                return null;
            }
        }
        return message;
    }

    private Message<?> frame(StompCommand command, String destination, Member user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            CustomUserDetails details = new CustomUserDetails(user.getId(), user.getEmail());
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    details, null, details.getAuthorities());
            accessor.setUser(authentication);
        }
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(new HashMap<>(Map.of(RequestIdFilter.MDC_KEY, "wiring-rid")));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 추적_ID_인터셉터가_인바운드_채널_맨_앞에_등록된다() {
        // 절대 인덱스 0은 검증 대상이 아니다: SimpleBrokerMessageHandler가 기동하며
        // UnsentDisconnectChannelInterceptor를 addInterceptor(0, ...)로 무조건 맨 앞에 꽂는다.
        // WebSocketConfig가 정한 순서 안에서 우리 인터셉터가 jwtAuth보다 앞인지를 본다.
        List<ChannelInterceptor> interceptors = clientInboundChannel.getInterceptors();
        int requestIdIndex = indexOfType(interceptors, WsRequestIdChannelInterceptor.class);
        int jwtAuthIndex = indexOfType(interceptors, JwtAuthChannelInterceptor.class);

        assertThat(requestIdIndex).isGreaterThanOrEqualTo(0);
        assertThat(requestIdIndex).isLessThan(jwtAuthIndex);
    }

    private int indexOfType(List<ChannelInterceptor> interceptors, Class<?> type) {
        for (int i = 0; i < interceptors.size(); i++) {
            if (type.isInstance(interceptors.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void preSend가_실제_인터셉터_체인에서_MDC에_추적_ID를_심는다() {
        Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/" + roomId, member);

        assertThat(through(message)).isNotNull();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("wiring-rid");
    }

    @Test
    void 핸들러_스레드에서도_추적_ID가_MDC에_있다() throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        MessageHandler handler = message -> {
            seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            latch.countDown();
        };

        clientInboundChannel.subscribe(handler);
        try {
            Message<?> message = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/" + roomId, member);
            clientInboundChannel.send(message);

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get()).isEqualTo("wiring-rid");
        } finally {
            clientInboundChannel.unsubscribe(handler);
        }
    }
}
