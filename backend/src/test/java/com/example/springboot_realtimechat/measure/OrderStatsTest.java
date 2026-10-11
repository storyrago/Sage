package com.example.springboot_realtimechat.measure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatsTest {

    @Test
    void 순서대로_빠짐없이_받으면_완전하다() {
        OrderStats stats = OrderStats.of(List.of(1L, 2L, 3L), 1, 3);

        assertThat(stats).isEqualTo(new OrderStats(3, 0, 0, 0));
        assertThat(stats.perfect()).isTrue();
    }

    @Test
    void 앞보다_작은_seq가_오면_인접_역전으로_센다() {
        OrderStats stats = OrderStats.of(List.of(2L, 1L, 4L, 3L), 1, 4);

        assertThat(stats.inversions()).isEqualTo(2);
        assertThat(stats.perfect()).isFalse();
    }

    @Test
    void 기대_범위에서_받지_못한_seq를_누락으로_센다() {
        OrderStats stats = OrderStats.of(List.of(1L, 3L), 1, 5);

        assertThat(stats.missing()).isEqualTo(3);
    }

    @Test
    void 같은_seq를_두_번_받으면_중복으로_센다() {
        OrderStats stats = OrderStats.of(List.of(1L, 2L, 2L), 1, 2);

        assertThat(stats.duplicates()).isEqualTo(1);
        // 중복은 역전이 아니다(같은 값은 작지 않다).
        assertThat(stats.inversions()).isZero();
    }

    @Test
    void 기대_범위_밖의_seq는_세지_않는다() {
        // 시나리오 2는 구간별로 잘라 본다. 다른 구간의 seq가 섞여도 이 구간의 통계만 낸다.
        OrderStats stats = OrderStats.of(List.of(1L, 21L, 22L, 2L), 21, 22);

        assertThat(stats).isEqualTo(new OrderStats(2, 0, 0, 0));
    }

    @Test
    void 요약_문자열에_네_값이_모두_들어간다() {
        assertThat(new OrderStats(3, 1, 2, 0).summary())
                .isEqualTo("received=3 inversions=1 missing=2 duplicates=0");
    }
}
