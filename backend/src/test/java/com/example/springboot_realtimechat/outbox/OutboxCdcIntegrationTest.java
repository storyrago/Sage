package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoom;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomMemberService;
import com.example.springboot_realtimechat.domain.chatroom.service.ChatRoomService;
import com.example.springboot_realtimechat.domain.member.entity.Member;
import com.example.springboot_realtimechat.domain.member.service.MemberService;
import com.example.springboot_realtimechat.domain.message.entity.Message;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;
import com.example.springboot_realtimechat.domain.message.service.MessageService;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메시지 저장 → binlog → Debezium(Outbox Event Router) → Kafka 경로 전체를 실제 컨테이너로 검증한다.
 * 환경 준비는 CdcTestEnvironment가 한다.
 * 컨테이너 네 개를 띄우므로 기본 test 태스크에서 빼고 ./gradlew cdcTest로 돌린다.
 */
@Tag("cdc")
@SpringBootTest(properties = "app.outbox.enabled=true")
class OutboxCdcIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CdcTestEnvironment.registerProperties(registry);
    }

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    // ---- 도우미 ----

    private List<ConsumerRecord<String, GenericRecord>> consumeUntil(Predicate<List<ConsumerRecord<String, GenericRecord>>> done) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CdcTestEnvironment.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "cdc-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                // 같은 토픽을 쓰는 다른 테스트가 넣은 해석 불가 이벤트에서 멈추지 않도록 감싸고, 값이 없는 레코드는 건너뛴다.
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class,
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class,
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CdcTestEnvironment.schemaRegistryUrl());
        List<ConsumerRecord<String, GenericRecord>> received = new ArrayList<>();
        try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(MessageEventSchema.TOPIC));
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ZERO).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.value() != null) received.add(r);
                });
                return done.test(received);
            });
        }
        return received;
    }

    private static List<ConsumerRecord<String, GenericRecord>> ofRoom(List<ConsumerRecord<String, GenericRecord>> records, ChatRoom room) {
        String key = String.valueOf(room.getId());
        return records.stream().filter(r -> key.equals(r.key())).toList();
    }

    private static boolean contains(List<ConsumerRecord<String, GenericRecord>> records, Message message, String eventType) {
        return records.stream().anyMatch(r -> message.getId().equals(r.value().get("messageId"))
                && eventType.equals(r.value().get("eventType").toString()));
    }

    private static String header(ConsumerRecord<String, GenericRecord> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Member joined(String email, ChatRoom... rooms) {
        Member member = memberService.create(email, "1234", "cdc작성자");
        for (ChatRoom room : rooms) chatRoomMemberService.join(member.getId(), room.getId(), null);
        return member;
    }

    // ---- 검증 ----

    @Test
    void 동시에_저장한_메시지가_방마다_한_파티션에서_순번_순서대로_도착한다() throws Exception {
        ChatRoom roomA = chatRoomService.create("cdc순서A", false, null);
        ChatRoom roomB = chatRoomService.create("cdc순서B", false, null);
        Member member = joined("cdc-order@e.com", roomA, roomB);
        int perRoom = 20;

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            ChatRoom room = (t % 2 == 0) ? roomA : roomB;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perRoom / 2; i++) {
                    messageService.create("m", null, member.getId(), room.getId(), null);
                }
            }));
        }
        for (Future<?> future : futures) future.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        Set<String> keys = Set.of(String.valueOf(roomA.getId()), String.valueOf(roomB.getId()));
        List<ConsumerRecord<String, GenericRecord>> received = consumeUntil(rs ->
                rs.stream().filter(r -> keys.contains(r.key())).count() >= 2L * perRoom);

        for (ChatRoom room : List.of(roomA, roomB)) {
            List<ConsumerRecord<String, GenericRecord>> events = ofRoom(received, room);
            assertThat(events).extracting(ConsumerRecord::partition).containsOnly(events.get(0).partition());
            // 파티션 안 도착 순서 = binlog 커밋 순서 = 순번 순서
            assertThat(events).extracting(r -> (Long) r.value().get("seq"))
                    .containsExactlyElementsOf(LongStream.rangeClosed(1, perRoom).boxed().toList());
            assertThat(events).allSatisfy(r -> {
                assertThat(r.value().get("eventType")).hasToString("CREATED");
                assertThat(header(r, "eventType")).isEqualTo("CREATED");
                assertThat(header(r, "id")).isEqualTo(r.value().get("eventId").toString());
                assertThat(r.value().get("chatroomId")).isEqualTo(room.getId());
            });
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
    }

    @Test
    void 롤백된_메시지의_이벤트는_나가지_않는다() {
        ChatRoom room = chatRoomService.create("cdc롤백방", false, null);
        Member member = joined("cdc-rollback@e.com", room);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            messageService.create("롤백될 메시지", null, member.getId(), room.getId(), null);
            status.setRollbackOnly();
        });
        // 같은 방(같은 파티션)의 다음 커밋이 도착하면 그 앞의 커밋은 모두 도착한 것이다.
        Message sentinel = messageService.create("센티널", null, member.getId(), room.getId(), null);

        List<ConsumerRecord<String, GenericRecord>> received = consumeUntil(rs -> contains(rs, sentinel, "CREATED"));

        List<ConsumerRecord<String, GenericRecord>> events = ofRoom(received, room);
        assertThat(events).extracting(r -> r.value().get("content").toString()).containsExactly("센티널");
        // 롤백은 순번 발급도 되돌린다.
        assertThat(events.get(0).value().get("seq")).isEqualTo(1L);
    }

    @Test
    void 수정과_삭제는_생성_뒤에_같은_파티션으로_순서대로_도착한다() {
        ChatRoom room = chatRoomService.create("cdc수정삭제방", false, null);
        Member member = joined("cdc-edit@e.com", room);
        Message message = messageService.create("원본", null, member.getId(), room.getId(), null);
        messageService.update(room.getId(), message.getId(), member.getId(), "수정본");
        messageService.delete(room.getId(), message.getId(), member.getId());

        List<ConsumerRecord<String, GenericRecord>> received = consumeUntil(rs -> contains(rs, message, "DELETED"));

        List<ConsumerRecord<String, GenericRecord>> events = ofRoom(received, room);
        assertThat(events).extracting(r -> r.value().get("eventType").toString())
                .containsExactly("CREATED", "UPDATED", "DELETED");
        assertThat(events.get(1).value().get("content")).hasToString("수정본");
        assertThat(events.get(2).value().get("deleted")).isEqualTo(true);
        assertThat(events.get(2).value().get("content")).hasToString("");
    }

    @Test
    void 재전송은_이벤트를_한_번만_낸다() {
        ChatRoom room = chatRoomService.create("cdc재전송방", false, null);
        Member member = joined("cdc-retry@e.com", room);
        String clientId = "5e4d3c2b-1a09-4f8e-9d7c-6b5a4f3e2d1c";

        messageService.create("한 번만", null, member.getId(), room.getId(), null, clientId);
        messageService.create("한 번만", null, member.getId(), room.getId(), null, clientId);
        Message sentinel = messageService.create("센티널", null, member.getId(), room.getId(), null);

        List<ConsumerRecord<String, GenericRecord>> received = consumeUntil(rs -> contains(rs, sentinel, "CREATED"));

        assertThat(ofRoom(received, room)).extracting(r -> r.value().get("content").toString())
                .containsExactly("한 번만", "센티널");
    }

    @Test
    void 레지스트리는_BACKWARD_호환되지_않는_스키마를_거부한다() throws Exception {
        SchemaRegistryClient client = CdcTestEnvironment.registryClient();
        String current = MessageEventSchema.SCHEMA.toString();
        // 기본값 없는 필드 추가: 새 스키마로 옛 이벤트를 읽을 수 없다.
        String breaking = current.replaceFirst("\"fields\":\\[", "\"fields\":[{\"name\":\"mustHave\",\"type\":\"string\"},");
        // 기본값 있는 선택 필드 추가: 옛 이벤트를 기본값으로 읽을 수 있다.
        String additive = current.replaceFirst("\"fields\":\\[",
                "\"fields\":[{\"name\":\"optional\",\"type\":[\"null\",\"string\"],\"default\":null},");

        assertThat(client.testCompatibility(MessageEventSchema.SUBJECT, new AvroSchema(breaking))).isFalse();
        assertThat(client.testCompatibility(MessageEventSchema.SUBJECT, new AvroSchema(additive))).isTrue();
    }
}
