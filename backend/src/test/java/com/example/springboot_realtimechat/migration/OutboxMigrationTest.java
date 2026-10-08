package com.example.springboot_realtimechat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

// payload는 스키마 레지스트리 와이어 포맷(매직 바이트 0x00 포함) 바이트다.
// 문자 집합 변환 없이 그대로 저장·복원되는지 실제 MySQL에서 확인한다.
class OutboxMigrationTest {

    private static MySQLContainer mysql;

    @BeforeAll
    static void startMySql() {
        mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"));
        mysql.start();
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopMySql() {
        if (mysql != null) mysql.stop();
    }

    @Test
    void payload는_모든_바이트값을_그대로_보존한다() throws Exception {
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; i++) allBytes[i] = (byte) i;

        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO outbox_events (id, aggregatetype, aggregateid, type, payload) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");
                insert.setString(2, "message");
                insert.setString(3, "1");
                insert.setString(4, "CREATED");
                insert.setBytes(5, allBytes);
                insert.executeUpdate();
            }
            try (PreparedStatement select = c.prepareStatement("SELECT payload FROM outbox_events WHERE id = ?")) {
                select.setString(1, "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b");
                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBytes("payload")).isEqualTo(allBytes);
                }
            }
        }
    }
}
