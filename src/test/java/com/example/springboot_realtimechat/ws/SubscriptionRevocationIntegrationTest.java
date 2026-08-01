package com.example.springboot_realtimechat.ws;

import com.example.springboot_realtimechat.security.CustomUserDetails;
import com.example.springboot_realtimechat.security.RoomSubscriptionRevoker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.broker.AbstractBrokerMessageHandler;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 컨텍스트에서 회수 프레임이 인터셉터를 통과해 브로커 구독 테이블까지 도달하는지 본다.
 * 인가 관련 빈은 mock 하지 않는다.
 */
@SpringBootTest
class SubscriptionRevocationIntegrationTest {

    private static final String SESSION_ID = "test-session";
    private static final Long MEMBER_ID = 7L;

    @Autowired RoomSubscriptionRevoker revoker;
    @Autowired ApplicationEventPublisher eventPublisher;

    // 빈 정의의 반환 타입이 AbstractBrokerMessageHandler라 타입만으로는 주입되지 않을 수 있다.
    @Autowired @Qualifier("simpleBrokerMessageHandler") AbstractBrokerMessageHandler brokerMessageHandler;

    private SimpleBrokerMessageHandler broker() {
        return (SimpleBrokerMessageHandler) brokerMessageHandler;
    }

    private Authentication user() {
        CustomUserDetails details = new CustomUserDetails(MEMBER_ID, "u@test.com");
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private Message<byte[]> frame(StompCommand command, String destination, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setUser(user());
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** 브로커 구독 테이블에 그 목적지의 구독이 남아 있는지 */
    private boolean subscribed(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return !broker().getSubscriptionRegistry().findSubscriptions(probe).isEmpty();
    }

    /** 인바운드 채널은 비동기라 반영까지 잠깐 기다린다. */
    private void awaitUnsubscribed(String destination) throws InterruptedException {
        for (int i = 0; i < 50 && subscribed(destination); i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void 회수하면_브로커_구독_테이블에서_사라진다() throws InterruptedException {
        // 브로커에 구독을 등록한다(동기)
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/sub/chatrooms/3", "sub-1");
        broker().getSubscriptionRegistry().registerSubscription(subscribe);

        // SimpUserRegistry를 채운다. 이 두 이벤트는 실제 소켓 프레임에서만 나오므로 직접 발행한다.
        eventPublisher.publishEvent(new SessionConnectedEvent(
                this, frame(StompCommand.CONNECTED, null, null), user()));
        eventPublisher.publishEvent(new SessionSubscribeEvent(this, subscribe, user()));

        assertThat(subscribed("/sub/chatrooms/3")).isTrue();

        revoker.revokeRoom(MEMBER_ID, 3L);
        awaitUnsubscribed("/sub/chatrooms/3");

        assertThat(subscribed("/sub/chatrooms/3")).isFalse();
    }
}
