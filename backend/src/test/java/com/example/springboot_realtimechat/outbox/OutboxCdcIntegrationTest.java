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
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * MySQL 설정·Debezium 계정·커넥터 JSON은 infra/local의 파일을 그대로 읽어, 로컬 환경과 같은 설정을 검증한다.
 * 컨테이너 네 개를 띄우므로 기본 test 태스크에서 빼고 ./gradlew cdcTest로 돌린다.
 */
@Tag("cdc")
@SpringBootTest(properties = "app.outbox.enabled=true")
class OutboxCdcIntegrationTest {

    private static final Path INFRA = Path.of("..", "infra", "local");
    private static final String PROBE_TOPIC = "chat.probe.events";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Network NETWORK = Network.newNetwork();

    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql")
            .withDatabaseName("spring_realtimechat_service")
            .withCopyFileToContainer(MountableFile.forHostPath(INFRA.resolve("mysql/conf.d/debezium.cnf")),
                    "/etc/mysql/conf.d/debezium.cnf")
            .withCopyFileToContainer(MountableFile.forHostPath(INFRA.resolve("mysql/init/01-debezium-user.sql")),
                    "/docker-entrypoint-initdb.d/01-debezium-user.sql");

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withListener("kafka:19092");

    private static final GenericContainer<?> SCHEMA_REGISTRY =
            new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:8.3.2"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("schema-registry")
                    .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
                    .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
                    .withEnv("SCHEMA_REGISTRY_KAFKASTORE_TOPIC_REPLICATION_FACTOR", "1")
                    .withExposedPorts(8081)
                    .dependsOn(KAFKA)
                    .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    // 브로커 1대라 Connect 내부 토픽 복제 수는 이미지 기본값(1)을 쓴다. 나머지 워커 설정은 compose와 같다.
    private static final GenericContainer<?> CONNECT =
            new GenericContainer<>(DockerImageName.parse("quay.io/debezium/connect:3.7.0.Final"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("connect")
                    .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
                    .withEnv("GROUP_ID", "sage-connect")
                    .withEnv("CONFIG_STORAGE_TOPIC", "sage-connect-configs")
                    .withEnv("OFFSET_STORAGE_TOPIC", "sage-connect-offsets")
                    .withEnv("STATUS_STORAGE_TOPIC", "sage-connect-status")
                    .withEnv("CONNECT_PRODUCER_ENABLE_IDEMPOTENCE", "true")
                    .withExposedPorts(8083)
                    .dependsOn(KAFKA, MYSQL)
                    .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private static final String schemaRegistryUrl;

    static {
        Startables.deepStart(MYSQL, KAFKA, SCHEMA_REGISTRY, CONNECT).join();
        schemaRegistryUrl = "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
        try {
            requireDebeziumMySqlSettings();
            // 탐침 행을 넣으려면 outbox 테이블이 먼저 있어야 한다. 스프링의 Flyway는 이후 적용분이 없어 그냥 지나간다.
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            registerSchema();
            createTopics();
            registerConnector();
            awaitStreaming();
        } catch (Exception e) {
            throw new IllegalStateException("CDC 테스트 환경 준비 실패", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.outbox.schema-registry-url", () -> schemaRegistryUrl);
    }

    @Autowired MessageService messageService;
    @Autowired MemberService memberService;
    @Autowired ChatRoomService chatRoomService;
    @Autowired ChatRoomMemberService chatRoomMemberService;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    // ---- 환경 준비 ----

    private static void requireDebeziumMySqlSettings() throws Exception {
        // infra/local/mysql/conf.d/debezium.cnf가 실제로 적용됐는지 확인한다(설정 파일이 무시되면 여기서 멈춘다).
        Map<String, String> vars = new HashMap<>();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW GLOBAL VARIABLES WHERE Variable_name IN "
                     + "('log_bin','binlog_format','binlog_row_image','gtid_mode','enforce_gtid_consistency','server_id')")) {
            while (rs.next()) vars.put(rs.getString(1), rs.getString(2));
        }
        assertThat(vars).containsEntry("log_bin", "ON")
                .containsEntry("binlog_format", "ROW")
                .containsEntry("binlog_row_image", "FULL")
                .containsEntry("gtid_mode", "ON")
                .containsEntry("enforce_gtid_consistency", "ON")
                .containsEntry("server_id", "223344");
    }

    private static SchemaRegistryClient registryClient() {
        return SchemaRegistryClientFactory.newClient(
                List.of(schemaRegistryUrl), 10, List.of(new AvroSchemaProvider()), Map.of(), Map.of());
    }

    /** infra/local/scripts/register-schema.sh와 같은 절차: 호환성 BACKWARD 고정 후 정규화 등록. */
    private static void registerSchema() throws Exception {
        SchemaRegistryClient client = registryClient();
        client.updateCompatibility(MessageEventSchema.SUBJECT, "BACKWARD");
        client.register(MessageEventSchema.SUBJECT, new AvroSchema(MessageEventSchema.SCHEMA), true);
    }

    private static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // 여러 파티션에서도 같은 방은 한 파티션에 모이는지 보려고 3개로 만든다.
            admin.createTopics(List.of(
                    new NewTopic(MessageEventSchema.TOPIC, 3, (short) 1),
                    new NewTopic(PROBE_TOPIC, 1, (short) 1))).all().get();
        }
    }

    private static String connectUrl() {
        return "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
    }

    private static void registerConnector() throws Exception {
        ObjectNode connector = (ObjectNode) JSON.readTree(Files.readString(INFRA.resolve("connect/outbox-connector.json")));
        // 로컬 compose는 브로커 3대, 테스트는 1대다. 이 값만 바꾸고 나머지는 파일 그대로 쓴다.
        ((ObjectNode) connector.get("config")).put("schema.history.internal.kafka.bootstrap.servers", "kafka:19092");
        HttpResponse<String> created = HTTP.send(HttpRequest.newBuilder(URI.create(connectUrl() + "/connectors"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(connector)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        String name = connector.get("name").asString();
        Awaitility.await().pollInSameThread().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            JsonNode status = JSON.readTree(HTTP.send(
                    HttpRequest.newBuilder(URI.create(connectUrl() + "/connectors/" + name + "/status")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            return "RUNNING".equals(status.path("connector").path("state").asString())
                    && status.path("tasks").size() == 1
                    && "RUNNING".equals(status.path("tasks").get(0).path("state").asString());
        });
    }

    /**
     * RUNNING이 돼도 binlog 읽기 시작 위치를 잡기 전일 수 있다.
     * 탐침 행(INSERT 후 DELETE)을 반복해 넣어 실제로 토픽에 도착할 때까지 기다린다.
     */
    private static void awaitStreaming() throws Exception {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                     ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
                     ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                     new StringDeserializer(), new ByteArrayDeserializer());
             Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            consumer.subscribe(List.of(PROBE_TOPIC));
            Awaitility.await().pollInSameThread().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(200)).until(() -> {
                insertProbe(c);
                return !consumer.poll(Duration.ofSeconds(1)).isEmpty();
            });
        }
    }

    private static void insertProbe(Connection c) throws Exception {
        String id = UUID.randomUUID().toString();
        c.setAutoCommit(false);
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO outbox_events (id, aggregatetype, aggregateid, type, payload) VALUES (?, 'probe', 'probe', 'PROBE', ?)");
             PreparedStatement delete = c.prepareStatement("DELETE FROM outbox_events WHERE id = ?")) {
            insert.setString(1, id);
            insert.setBytes(2, new byte[]{0});
            insert.executeUpdate();
            delete.setString(1, id);
            delete.executeUpdate();
        }
        c.commit();
        c.setAutoCommit(true);
    }

    // ---- 도우미 ----

    private List<ConsumerRecord<String, GenericRecord>> consumeUntil(Predicate<List<ConsumerRecord<String, GenericRecord>>> done) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "cdc-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class,
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        List<ConsumerRecord<String, GenericRecord>> received = new ArrayList<>();
        try (KafkaConsumer<String, GenericRecord> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(MessageEventSchema.TOPIC));
            Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ZERO).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(received::add);
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
        SchemaRegistryClient client = registryClient();
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
