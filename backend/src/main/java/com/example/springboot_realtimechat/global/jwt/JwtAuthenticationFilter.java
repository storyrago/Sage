package com.example.springboot_realtimechat.global.jwt;

import com.example.springboot_realtimechat.global.auth.CustomUserDetails;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;

    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    )throws ServletException, IOException{
        String authorizationHeader = request.getHeader("Authorization");
        if(authorizationHeader != null && authorizationHeader.startsWith("Bearer ")){
            String token = authorizationHeader.substring(7);
            if(jwtTokenProvider.validateToken(token)){
                Long memberId = jwtTokenProvider.getMemberId(token);
                String email = jwtTokenProvider.getEmail(token);

                // 서명과 만료를 통과해도 로그아웃·탈퇴로 무효화된 토큰은 인증하지 않는다.
                boolean revoked = tokenDenylist.isRevoked(
                        jwtTokenProvider.getJti(token),
                        memberId,
                        jwtTokenProvider.getIssuedAt(token));

                if (!revoked) {
                    CustomUserDetails customUserDetails = new CustomUserDetails(memberId, email);

                    UsernamePasswordAuthenticationToken usernamePasswordAuthenticationToken =
                            new UsernamePasswordAuthenticationToken(
                                    customUserDetails,
                                    null,
                                    customUserDetails.getAuthorities()
                            );
                    SecurityContextHolder.getContext().setAuthentication(usernamePasswordAuthenticationToken);
                }
            }
        }

        //jwt 인증
        filterChain.doFilter(request,response);
    }

}
