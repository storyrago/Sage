package com.example.springboot_realtimechat.room;

import com.example.springboot_realtimechat.service.InviteCodeGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void 코드는_12자다() {
        assertThat(generator.generate()).hasSize(12);
    }

    @Test
    void 혼동되는_문자는_쓰지_않는다() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).doesNotContainAnyWhitespaces()
                    .matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{12}");
        }
    }

    @Test
    void 연달아_뽑아도_겹치지_않는다() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(1000);
    }
}
