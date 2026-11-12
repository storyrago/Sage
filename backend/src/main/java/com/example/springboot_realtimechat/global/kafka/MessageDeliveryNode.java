package com.example.springboot_realtimechat.global.kafka;

/** 이 서버(노드)의 식별자. 서버별 consumer group 이름에 쓴다(@KafkaListener의 groupId SpEL이 id()를 읽는다). */
public record MessageDeliveryNode(String id) {
}
