package com.chat.project.chat.dto.response;

public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private UserResponse user;

    public AuthResponse() {}

    private AuthResponse(Builder b) {
        this.accessToken = b.accessToken;
        this.refreshToken = b.refreshToken;
        this.user = b.user;
    }

    public static Builder builder() { return new Builder(); }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public UserResponse getUser() { return user; }

    public static class Builder {
        private String accessToken;
        private String refreshToken;
        private UserResponse user;

        public Builder accessToken(String v) { this.accessToken = v; return this; }
        public Builder refreshToken(String v) { this.refreshToken = v; return this; }
        public Builder user(UserResponse v) { this.user = v; return this; }
        public AuthResponse build() { return new AuthResponse(this); }
    }
}