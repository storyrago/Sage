package com.example.springboot_realtimechat.domain;

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
@Table(name = "members")
public class Member {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column
    private String password;

    @Column(nullable = false, length = 20)
    private String provider = "LOCAL";

    @Column(name = "google_sub", length = 255, unique = true)
    private String googleSub;

    @Column(length=10)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createdAt;

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

    public static Member ofGoogle(String email, String nickname, String profileImageUrl, String googleSub) {
        Member m = new Member();
        m.email = email;
        m.password = null;
        m.nickname = nickname;
        m.profileImageUrl = profileImageUrl;
        m.provider = "GOOGLE";
        m.googleSub = googleSub;
        return m;
    }

    public void linkGoogle(String googleSub) {
        this.googleSub = googleSub;
    }

    public void updateEmail(String email) {
        this.email = email;
    }
}

