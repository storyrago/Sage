package com.example.springboot_realtimechat.presence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceRegistryTest {

    @Test
    void 접속하면_온라인_목록에_포함된다() {
        PresenceRegistry registry = new PresenceRegistry();

        registry.connect("session-1", 10L);

        assertThat(registry.getOnlineMemberIds()).containsExactly(10L);
    }

    @Test
    void 같은_멤버가_여러_세션이면_한_명으로_집계된다() {
        PresenceRegistry registry = new PresenceRegistry();

        registry.connect("session-1", 10L);
        registry.connect("session-2", 10L);

        assertThat(registry.getOnlineMemberIds()).containsExactly(10L);
    }

    @Test
    void 마지막_세션이_끊겨야_오프라인이_된다() {
        PresenceRegistry registry = new PresenceRegistry();
        registry.connect("session-1", 10L);
        registry.connect("session-2", 10L);

        registry.disconnect("session-1");
        assertThat(registry.getOnlineMemberIds()).containsExactly(10L);

        registry.disconnect("session-2");
        assertThat(registry.getOnlineMemberIds()).isEmpty();
    }

    @Test
    void 끊긴_세션의_memberId를_반환한다() {
        PresenceRegistry registry = new PresenceRegistry();
        registry.connect("session-1", 10L);

        assertThat(registry.disconnect("session-1")).contains(10L);
        assertThat(registry.disconnect("unknown-session")).isEmpty();
    }
}
