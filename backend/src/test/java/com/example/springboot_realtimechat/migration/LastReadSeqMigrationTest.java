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
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 백필 SQL은 MySQL에서 돌므로 CI와 같은 MySQL 8.0에서 V11까지 올린 뒤 포인터를 바꾸고 V12를 적용해 확인한다.
// 시드(V8): 방1 메시지 id 1~3 = seq 1~3(작성자 2,1,2), 방2 메시지 id 4~9 = seq 1~6(작성자 2,1,2,2,1,2).
// V13은 V12가 백필에 쓴 last_read_message_id를 지운다. 첫 테스트는 target 11에서 그 컬럼을 고친 뒤 끝까지 올린다.
class LastReadSeqMigrationTest {

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
    void 읽음_포인터와_본인_메시지_중_더_뒤의_순번으로_채운다() throws Exception {
        flyway("11").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // 멤버십 1: 포인터 없음 → 본인 메시지(id 2 = seq 2)만 남는다.
            s.executeUpdate("UPDATE chatroom_members SET last_read_message_id = NULL WHERE id = 1");
            // 멤버십 4: 방에 없는 큰 id → 그 이하 최대인 id 9 = seq 6.
            s.executeUpdate("UPDATE chatroom_members SET last_read_message_id = 100 WHERE id = 4");
        }

        flyway("latest").migrate();

        Map<Long, Long> lastReadSeqById = new HashMap<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, last_read_seq FROM chatroom_members")) {
            while (rs.next()) lastReadSeqById.put(rs.getLong("id"), rs.getLong("last_read_seq"));
        }
        assertThat(lastReadSeqById).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, 2L,   // 포인터 없음, 본인 메시지 seq 2
                2L, 3L,   // 포인터 id 3 = seq 3
                3L, 5L,   // 포인터 id 5 = seq 2지만 본인 메시지 id 8 = seq 5가 더 뒤
                4L, 6L)); // 포인터 id 100 → id 9 = seq 6
    }

    @Test
    void 새_멤버십은_0에서_시작한다() throws Exception {
        flyway("latest").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM chatroom_members WHERE id = 4");
            s.executeUpdate("INSERT INTO chatroom_members (id, member_id, chatroom_id) VALUES (4, 2, 2)");
            try (ResultSet rs = s.executeQuery("SELECT last_read_seq FROM chatroom_members WHERE id = 4")) {
                rs.next();
                assertThat(rs.getLong("last_read_seq")).isZero();
            }
        }
    }

    @Test
    void 끝까지_올리면_메시지_id_읽음_컬럼이_없다() throws Exception {
        flyway("latest").migrate();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'chatroom_members'
                       AND COLUMN_NAME = 'last_read_message_id'""")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
