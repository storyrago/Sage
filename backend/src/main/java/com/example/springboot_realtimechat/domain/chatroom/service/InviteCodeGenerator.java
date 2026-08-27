package com.example.springboot_realtimechat.domain.chatroom.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 방 초대 코드를 만든다.
 * 코드 길이가 곧 보안이므로 줄이지 않는다 — 짧아지면 추측이 가능해져 시도 제한이 필요해진다.
 */
@Component
public class InviteCodeGenerator {

    // 0/O, 1/l/I 처럼 눈으로 구별하기 어려운 문자를 뺀 32자다.
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
