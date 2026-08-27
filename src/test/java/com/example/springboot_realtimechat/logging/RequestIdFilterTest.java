package com.example.springboot_realtimechat.logging;

import com.example.springboot_realtimechat.global.common.RequestIdFilter;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// RequestIdFilter가 추적 ID를 어떻게 정하고, 어디에 싣고, 언제 지우는지 검증한다.
// 가장 중요한 건 MDC 정리다 — 톰캣은 스레드를 재사용하므로 안 지우면 다음 요청 로그에 남의 추적 id가 샌다.
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void 들어온_X_Request_Id를_그대로_쓴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "incoming-trace-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("incoming-trace-id-123");
    }

    @Test
    void 헤더가_없으면_새로_만든다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        String generated = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(generated).isNotBlank();
        // UUID.randomUUID() 형식(대시 포함 36자)인지 확인 — 이 레포의 관례를 따른다
        assertThat(generated).matches("[0-9a-f-]{36}");
    }

    @Test
    void 너무_긴_값은_거부하고_새로_만든다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "a".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        String used = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(used).doesNotContain("a".repeat(200));
        assertThat(used).matches("[0-9a-f-]{36}");
    }

    @Test
    void 이상한_문자가_섞인_값은_거부하고_새로_만든다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "trace\nid\r개행섞임");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        String used = response.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(used).matches("[0-9a-f-]{36}");
    }

    @Test
    void 정상_처리_후_요청이_끝나면_MDC가_비워진다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, noopChain());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 체인_중_MDC에_추적_ID가_실제로_들어가_있다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "mid-chain-check");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain assertingChain = (req, res) ->
                assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("mid-chain-check");

        filter.doFilter(request, response, assertingChain);
    }

    @Test
    void 체인에서_예외가_나도_MDC가_비워진다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain throwingChain = (req, res) -> {
            throw new RuntimeException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(RuntimeException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    private FilterChain noopChain() {
        return (req, res) -> { };
    }
}
