package com.example.springboot_realtimechat.global.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청마다 추적 ID를 MDC에 심어, 한 요청에서 나온 로그를 이어붙일 수 있게 한다.
 * nginx가 X-Request-Id 헤더로 자신의 $request_id를 넘겨주면 그걸 그대로 쓰고,
 * 없거나 형식이 이상하면 새로 발급한다. 응답 헤더에도 실어 사용자가 오류를 신고할 때 알려줄 수 있게 한다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // nginx $request_id(대시 없는 32자 hex)와 UUID(36자, 대시 포함) 양쪽을 허용한다.
    // 외부에서 온 값을 검증 없이 그대로 MDC·응답 헤더에 실으면 로그 오염·위조(개행 삽입 등)가 가능하다.
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[a-zA-Z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER_NAME));

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 톰캣은 스레드를 재사용한다. 여기서 지우지 않으면 다음 요청 로그에 이번 요청의 추적 ID가 남는다.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(String incoming) {
        if (incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
