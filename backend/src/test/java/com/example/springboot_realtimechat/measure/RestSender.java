package com.example.springboot_realtimechat.measure;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** 실제 전송 경로(REST)로 메시지를 보낸다. 상태 코드만 돌려준다 — 실패도 측정 대상이라 예외로 멈추지 않는다. */
final class RestSender {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private RestSender() {
    }

    /** @return HTTP 상태 코드. 응답을 받지 못하면 -1. */
    static int send(int port, String token, long roomId, String content) {
        String body = JSON.writeValueAsString(Map.of(
                "content", content,
                "clientMessageId", UUID.randomUUID().toString()));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/chatrooms/" + roomId + "/messages"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
