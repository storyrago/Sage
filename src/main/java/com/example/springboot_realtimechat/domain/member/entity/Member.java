package com.example.springboot_realtimechat.domain.member.entity;

import com.example.springboot_realtimechat.domain.chatroom.entity.ChatRoomMember;
import com.example.springboot_realtimechat.domain.message.entity.Message;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor
@Table(
        name = "members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_members_provider",
                columnNames = {"provider", "provider_id"}
        )
)
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String email;

    @Column
    private String password;

    @Column(nullable = false, length = 20)
    private String provider = "LOCAL";

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Column(length = 20)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(name = "onboarded_at")
    private LocalDateTime onboardedAt;

    @OneToMany(mappedBy = "member")
    private List<Message> messages = new ArrayList<>();

    @OneToMany(mappedBy = "member")
    private List<ChatRoomMember> chatRoomMembers = new ArrayList<>();

    public Member(String email, String password, String nickname) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
    }

    public void updateProfileImageUrl(String profileImageUrl){
        this.profileImageUrl = profileImageUrl;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void markOnboarded() {
        if (this.onboardedAt == null) {
            this.onboardedAt = LocalDateTime.now();
        }
    }

    public boolean isOnboarded() {
        return this.onboardedAt != null;
    }

    public static Member ofSocial(String provider, String providerId, String email,
                                  String nickname, String profileImageUrl) {
        Member m = new Member();
        m.provider = provider;
        m.providerId = providerId;
        m.email = email;
        m.password = null;
        m.nickname = nickname;
        m.profileImageUrl = usablePicture(profileImageUrl);
        return m;
    }

    // 제공자가 주는 사진 주소는 우리가 통제하지 않는 값이다. 컬럼 한계를 넘거나 http(s) 주소가 아니면
    // 버리고 가입은 진행한다(아바타는 이니셜로 폴백한다).
    private static String usablePicture(String url) {
        if (url == null || url.length() > 500) return null;
        return (url.startsWith("https://") || url.startsWith("http://")) ? url : null;
    }

    public void updateEmail(String email) {
        this.email = email;
    }
}

