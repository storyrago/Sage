package com.example.springboot_realtimechat.outbox;

import com.example.springboot_realtimechat.global.outbox.OutboxEvent;
import com.example.springboot_realtimechat.global.outbox.OutboxWriter;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.EntityStatistics;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// CDC는 binlog의 INSERT를 읽는다. persist 직후 remove가 쓰기 지연 중 상쇄되면 binlog에 아무것도 남지 않으므로
// INSERT와 DELETE가 실제로 실행됐는지를 Hibernate 통계로 확인한다.
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OutboxWriterTest {

    @Autowired OutboxWriter outboxWriter;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    private OutboxEvent sampleEvent() {
        return new OutboxEvent(UUID.randomUUID().toString(), "message", "1", "CREATED", new byte[]{0, 1, 2});
    }

    @Test
    void 같은_트랜잭션에서_INSERT와_DELETE를_실행하고_행은_남기지_않는다() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> outboxWriter.append(sampleEvent()));

        EntityStatistics outbox = statistics.getEntityStatistics(OutboxEvent.class.getName());
        assertThat(outbox.getInsertCount()).isEqualTo(1);
        assertThat(outbox.getDeleteCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
    }

    @Test
    void 트랜잭션_밖에서는_쓸_수_없다() {
        // 업무 변경과 함께 커밋·롤백되지 않는 이벤트는 outbox의 의미가 없다.
        assertThatThrownBy(() -> outboxWriter.append(sampleEvent()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
