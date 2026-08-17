package com.chat.project.chat.dto.response;

import com.chat.project.chat.entity.Group;

import java.time.Instant;
import java.util.List;

public class GroupResponse {
    private Long id;
    private String name;
    private String avatar;
    private UserResponse owner;
    private List<UserResponse> members;
    private int memberCount;
    private Instant createdAt;

    private GroupResponse() {}

    private GroupResponse(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.avatar = b.avatar;
        this.owner = b.owner;
        this.members = b.members;
        this.memberCount = b.memberCount;
        this.createdAt = b.createdAt;
    }

    public static GroupResponse from(Group g, UserResponse owner, List<UserResponse> members) {
        return builder()
                .id(g.getId())
                .name(g.getName())
                .avatar(g.getAvatar())
                .owner(owner)
                .members(members)
                .memberCount(members.size())
                .createdAt(g.getCreatedAt())
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getAvatar() { return avatar; }
    public UserResponse getOwner() { return owner; }
    public List<UserResponse> getMembers() { return members; }
    public int getMemberCount() { return memberCount; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Builder {
        private Long id;
        private String name;
        private String avatar;
        private UserResponse owner;
        private List<UserResponse> members;
        private int memberCount;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder avatar(String v) { this.avatar = v; return this; }
        public Builder owner(UserResponse v) { this.owner = v; return this; }
        public Builder members(List<UserResponse> v) { this.members = v; return this; }
        public Builder memberCount(int v) { this.memberCount = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public GroupResponse build() { return new GroupResponse(this); }
    }
}