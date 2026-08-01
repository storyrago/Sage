package com.example.springboot_realtimechat.repository;

import com.example.springboot_realtimechat.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);

    Optional<Member> findByProviderAndProviderId(String provider, String providerId);

    boolean existsByProfileImageUrl(String profileImageUrl);
}
