package com.example.springboot_realtimechat.global.config;

import com.example.springboot_realtimechat.global.common.RequestIdFilter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 요청 추적 ID 필터를 서블릿 필터 체인 맨 앞에 건다.
 * Spring Security 필터 체인(springSecurityFilterChain)은 SecurityFilterProperties.DEFAULT_FILTER_ORDER(-100)로
 * 등록되므로, 그보다 먼저(Ordered.HIGHEST_PRECEDENCE) 돌아야 그 안의 JwtAuthenticationFilter를 포함한
 * 모든 필터의 로그에도 추적 ID가 붙는다.
 */
@Configuration
public class RequestIdFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
