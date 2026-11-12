package com.example.springboot_realtimechat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 백필 SQL은 MySQL 전용 문법(UPDATE ... JOIN, 윈도 함수)이라 H2로는 검증할 수 없다.
// CI와 같은 MySQL 8.0에서 V9까지 올린 뒤 데이터를 넣고 V10을 적용해 결과를 확인한다.
class RoomMessageSeqMigrationTest {

    private static MySQLContainer mysql;

    @BeforeAll
    static void startMySql() {
        mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"));
        mysql.start();
    }

    @AfterAll
    static void stopMySql() {
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void cleanSchema() {
        flyway("latest").clean();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    @Test
    void 기존_메시지에_방별_순번을_id_순서로_채운다() throws Exception {
        flyway("9").migrate();
        // 두 방에 번갈아 넣어 id 순서와 방 안 순서가 다르게 만든다.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO messages (id, content, member_id, chatroom_id, created_at) VALUES
                      (10, 'a', 1, 1, NOW(6)),
                      (11, 'b', 1, 2, NOW(6)),
                      (12, 'c', 1, 1, NOW(6))
                    """);
        }

        flyway("latest").migrate();

        Map<Long, Long> seqById = new HashMap<>();
        Map<Long, Long> lastSeqByRoom = new HashMap<>();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT id, seq FROM messages")) {
                while (rs.next()) seqById.put(rs.getLong("id"), rs.getLong("seq"));
            }
            try (ResultSet rs = s.executeQuery("SELECT id, last_message_seq FROM chatrooms")) {
                while (rs.next()) lastSeqByRoom.put(rs.getLong("id"), rs.getLong("last_message_seq"));
            }
        }
        assertThat(seqById).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry(1L, 1L), Map.entry(2L, 2L), Map.entry(3L, 3L),
                Map.entry(10L, 4L), Map.entry(12L, 5L),
                Map.entry(4L, 1L), Map.entry(5L, 2L), Map.entry(6L, 3L),
                Map.entry(7L, 4L), Map.entry(8L, 5L), Map.entry(9L, 6L),
                Map.entry(11L, 7L)));
        assertThat(lastSeqByRoom).containsEntry(1L, 5L).containsEntry(2L, 7L);
    }

    @Test
    void 같은_방에_같은_순번은_들어갈_수_없다() throws Exception {
        flyway("latest").migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate(
                    "INSERT INTO messages (content, member_id, chatroom_id, created_at, seq) VALUES ('dup', 1, 1, NOW(6), 1)"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
        }
    }

    @Test
    void 같은_회원은_같은_clientMessageId를_두_번_쓸_수_없다() throws Exception {
        flyway("latest").migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO messages (content, member_id, chatroom_id, created_at, seq, client_message_id)
                    VALUES ('x', 1, 1, NOW(6), 100, '11111111-1111-1111-1111-111111111111')
                    """);
            assertThatThrownBy(() -> s.executeUpdate("""
                    INSERT INTO messages (content, member_id, chatroom_id, created_at, seq, client_message_id)
                    VALUES ('y', 1, 2, NOW(6), 100, '11111111-1111-1111-1111-111111111111')
                    """))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            // 회원이 다르면 같은 값을 써도 된다(UUID는 클라이언트마다 따로 만든다).
            s.executeUpdate("""
                    INSERT INTO messages (content, member_id, chatroom_id, created_at, seq, client_message_id)
                    VALUES ('z', 2, 1, NOW(6), 101, '11111111-1111-1111-1111-111111111111')
                    """);
        }
    }
}
