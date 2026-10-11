package com.example.springboot_realtimechat.measure;

import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 실제 STOMP 웹소켓 클라이언트로 한 서버에 접속해 방을 구독하고, 받은 메시지를 도착 순서대로 기록한다.
 * 측정 지점이 "사용자가 받은 것"이라 두 모드를 같은 자리에서 비교할 수 있다.
 */
final class StompSubscriber implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebSocketStompClient client;
    private final StompSession session;
    private final List<Received> received = new CopyOnWriteArrayList<>();

    /** 도착 순서대로 기록한 한 건. seq와 본문 — 시나리오 2 구간 경계를 본문 접두어로 가르는 데 쓴다. */
    record Received(long seq, String content) {
    }

    private StompSubscriber(WebSocketStompClient client, StompSession session) {
        this.client = client;
        this.session = session;
    }

    static StompSubscriber connect(int port, String token, long roomId) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        try {
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer " + token);
            StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
                            new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                            })
                    .get(10, TimeUnit.SECONDS);
            StompSubscriber subscriber = new StompSubscriber(client, session);
            session.subscribe("/sub/chatrooms/" + roomId, subscriber.recorder());
            return subscriber;
        } catch (Exception e) {
            client.stop();
            throw e;
        }
    }

    private StompFrameHandler recorder() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                JsonNode node = JSON.readTree((byte[]) payload);
                received.add(new Received(node.get("seq").asLong(), node.get("content").asString()));
            }
        };
    }

    /** 지금까지 받은, seq와 본문을 함께 담은 목록(도착 순). 복사본이다. */
    List<Received> received() {
        return new ArrayList<>(received);
    }

    /** 지금까지 받은 seq(도착 순). 복사본이다. */
    List<Long> seqs() {
        return received.stream().map(Received::seq).toList();
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }
}
