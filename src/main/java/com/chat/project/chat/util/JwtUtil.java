package com.chat.project.chat.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    // jti 黑名单：jti → 过期时间戳（ms），注销/删号后立即失效
    private final Map<String, Long> revokedJtis = new ConcurrentHashMap<>();

    public JwtUtil(
            @Value("${webchat.jwt.secret}") String secret,
            @Value("${webchat.jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${webchat.jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "JWT_SECRET 环境变量未设置，请执行 openssl rand -base64 64 生成密钥");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT 密钥长度不足 32 字节（256 位），无法用于 HMAC-SHA256。" +
                    "当前长度：" + keyBytes.length + " 字节，请设置更长的 JWT_SECRET 环境变量。");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String generateAccessToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("type", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public boolean isAccessToken(String token) {
        return "access".equals(parseToken(token).get("type", String.class));
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 吊销一个 access token（注销/删号时调用） */
    public void revokeToken(String token) {
        try {
            Claims claims = parseToken(token);
            String jti = claims.getId();
            if (jti != null) revokedJtis.put(jti, claims.getExpiration().getTime());
        } catch (Exception ignored) {}
    }

    /** 检查 token 是否已被吊销 */
    public boolean isRevoked(String token) {
        try {
            String jti = parseToken(token).getId();
            return jti != null && revokedJtis.containsKey(jti);
        } catch (Exception e) {
            return true;
        }
    }

    /** 每 5 分钟清理已自然过期的 jti */
    @Scheduled(fixedDelay = 300_000)
    public void purgeExpiredRevokedJtis() {
        long now = System.currentTimeMillis();
        revokedJtis.entrySet().removeIf(e -> e.getValue() < now);
    }
}