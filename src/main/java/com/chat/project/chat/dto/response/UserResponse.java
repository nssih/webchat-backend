package com.chat.project.chat.dto.response;

import com.chat.project.chat.entity.User;

import java.time.Instant;

public class UserResponse {
    private Long id;
    private String uid;
    private String username;
    private String nickname;
    private String avatar;
    private Instant createdAt;

    private UserResponse() {}

    private UserResponse(Builder b) {
        this.id = b.id;
        this.uid = b.uid;
        this.username = b.username;
        this.nickname = b.nickname;
        this.avatar = b.avatar;
        this.createdAt = b.createdAt;
    }

    public static UserResponse from(User user) {
        return builder()
                .id(user.getId())
                .uid(user.getUid())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public Long getId() { return id; }
    public String getUid() { return uid; }
    public String getUsername() { return username; }
    public String getNickname() { return nickname; }
    public String getAvatar() { return avatar; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Builder {
        private Long id;
        private String uid;
        private String username;
        private String nickname;
        private String avatar;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder uid(String v) { this.uid = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder nickname(String v) { this.nickname = v; return this; }
        public Builder avatar(String v) { this.avatar = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public UserResponse build() { return new UserResponse(this); }
    }
}