package com.chat.project.chat.dto.response;

import com.chat.project.chat.entity.Friendship;

import java.time.Instant;

public class FriendResponse {
    private Long friendshipId;
    private UserResponse user;
    private String status;
    private Instant createdAt;

    private FriendResponse() {}

    private FriendResponse(Builder b) {
        this.friendshipId = b.friendshipId;
        this.user = b.user;
        this.status = b.status;
        this.createdAt = b.createdAt;
    }

    public static FriendResponse from(Friendship f, UserResponse user) {
        return builder()
                .friendshipId(f.getId())
                .user(user)
                .status(f.getStatus().name())
                .createdAt(f.getCreatedAt())
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public Long getFriendshipId() { return friendshipId; }
    public UserResponse getUser() { return user; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Builder {
        private Long friendshipId;
        private UserResponse user;
        private String status;
        private Instant createdAt;

        public Builder friendshipId(Long v) { this.friendshipId = v; return this; }
        public Builder user(UserResponse v) { this.user = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public FriendResponse build() { return new FriendResponse(this); }
    }
}