package com.example.springboot_realtimechat.measure;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 실제 STOMP 웹소켓 클라이언트로 한 서버에 접속해 방을 구독하고, 받은 메시지의 seq를 도착 순서대로 기록한다.
 * 측정 지점이 "사용자가 받은 것"이라 두 모드를 같은 자리에서 비교할 수 있다.
 */
final class StompSubscriber implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocketStompClient client;
    private final StompSession session;
    private final List<Long> seqs = new CopyOnWriteArrayList<>();

    private StompSubscriber(WebSocketStompClient client, StompSession session) {
        this.client = client;
        this.session = session;
    }

    static StompSubscriber connect(int port, String token, long roomId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                        })
                .get(10, TimeUnit.SECONDS);
        StompSubscriber subscriber = new StompSubscriber(client, session);
        session.subscribe("/sub/chatrooms/" + roomId, subscriber.recorder());
        return subscriber;
    }

    private StompFrameHandler recorder() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                seqs.add(JSON.readTree((byte[]) payload).get("seq").asLong());
            }
        };
    }

    /** 지금까지 받은 seq(도착 순). 복사본이다. */
    List<Long> seqs() {
        return new ArrayList<>(seqs);
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }
}
