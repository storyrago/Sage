package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.domain.image.event.MessageImageCleanupConsumer;
import com.example.springboot_realtimechat.domain.message.event.MessageEventSchema;

import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClientFactory;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CDC 통합 테스트들이 함께 쓰는 컨테이너(MySQL·Kafka·스키마 레지스트리·Debezium Connect)와 준비 절차. JVM당 한 번 띄운다.
 * MySQL 설정·Debezium 계정·커넥터 JSON은 infra/local의 파일을 그대로 읽어, 로컬 환경과 같은 설정을 검증한다.
 */
public final class CdcTestEnvironment {

    public static final String PROBE_TOPIC = "chat.probe.events";
    // 여러 파티션에서도 같은 방은 한 파티션에 모이는지 보려고 3개로 만든다. DLT도 같은 수.
    public static final int TOPIC_PARTITIONS = 3;

    private static final Path INFRA = Path.of("..", "infra", "local");
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

    private static boolean started;

    private CdcTestEnvironment() {
    }

    /** 처음 부를 때 컨테이너를 띄우고 스키마·토픽·커넥터를 준비한다. 실패하면 다음 호출이 다시 시도한다. */
    public static synchronized void start() {
        if (started) {
            return;
        }
        Startables.deepStart(MYSQL, KAFKA, SCHEMA_REGISTRY, CONNECT).join();
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
        started = true;
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.outbox.schema-registry-url", CdcTestEnvironment::schemaRegistryUrl);
        registry.add("spring.kafka.bootstrap-servers", CdcTestEnvironment::bootstrapServers);
    }

    public static String jdbcUrl() {
        return MYSQL.getJdbcUrl();
    }

    public static String username() {
        return MYSQL.getUsername();
    }

    public static String password() {
        return MYSQL.getPassword();
    }

    public static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    public static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    public static SchemaRegistryClient registryClient() {
        return SchemaRegistryClientFactory.newClient(
                List.of(schemaRegistryUrl()), 10, List.of(new AvroSchemaProvider()), Map.of(), Map.of());
    }

    // ---- 준비 절차 ----

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

    /** infra/local/scripts/register-schema.sh와 같은 절차: 호환성 BACKWARD 고정 후 정규화 등록. */
    private static void registerSchema() throws Exception {
        SchemaRegistryClient client = registryClient();
        client.updateCompatibility(MessageEventSchema.SUBJECT, "BACKWARD");
        client.register(MessageEventSchema.SUBJECT, new AvroSchema(MessageEventSchema.SCHEMA), true);
    }

    private static void createTopics() throws Exception {
        List<NewTopic> topics = new ArrayList<>(List.of(
                new NewTopic(MessageEventSchema.TOPIC, TOPIC_PARTITIONS, (short) 1),
                new NewTopic(MessageEventSchema.DEAD_LETTER_TOPIC, TOPIC_PARTITIONS, (short) 1),
                new NewTopic(MessageImageCleanupConsumer.DEAD_LETTER_TOPIC, TOPIC_PARTITIONS, (short) 1),
                new NewTopic(PROBE_TOPIC, 1, (short) 1)));
        for (String retryTopic : MessageImageCleanupConsumer.RETRY_TOPICS) {
            topics.add(new NewTopic(retryTopic, TOPIC_PARTITIONS, (short) 1));
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(topics).all().get();
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
}
