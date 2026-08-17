package com.chat.project.chat.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Value("${webchat.rate-limit.register-max:5}")
    private int registerMax;

    @Value("${webchat.rate-limit.register-window-ms:3600000}")
    private long registerWindowMs;

    @Value("${webchat.rate-limit.login-max:10}")
    private int loginMax;

    @Value("${webchat.rate-limit.login-window-ms:60000}")
    private long loginWindowMs;

    // key = "type:ip" → 请求时间戳队列
    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    // key = "type:ip" → 最后访问时间（用于定时清理）
    private final ConcurrentHashMap<String, Long> lastSeen = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("POST".equals(method)) {
            String ip = getClientIp(request);
            if (path.equals("/api/auth/login") && isLimited("login:" + ip, loginMax, loginWindowMs)) {
                rejectWith429(response);
                return;
            }
            if (path.equals("/api/auth/register") && isLimited("register:" + ip, registerMax, registerWindowMs)) {
                rejectWith429(response);
                return;
            }
            if (path.equals("/api/auth/refresh") && isLimited("refresh:" + ip, 30, 60_000)) {
                rejectWith429(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isLimited(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        lastSeen.put(key, now);
        Deque<Long> dq = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst() < now - windowMs) dq.pollFirst();
            if (dq.size() >= limit) return true;
            dq.addLast(now);
            return false;
        }
    }

    private void rejectWith429(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            objectMapper.writeValueAsString(Map.of("success", false, "message", "请求过于频繁，请稍后再试"))
        );
    }

    private String getClientIp(HttpServletRequest request) {
        // 取 X-Forwarded-For 的最后一个 IP（由可信代理 Render 追加，不可被客户端伪造）
        // 第一个 IP 是客户端自报，可被伪造绕过限流；X-Real-IP 同样可被客户端伪造，不使用
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    /** 每分钟清理超过 10 分钟不活跃的 bucket，防止内存泄漏 */
    @Scheduled(fixedDelay = 60_000)
    public void purgeInactiveBuckets() {
        long cutoff = System.currentTimeMillis() - 10 * 60_000;
        lastSeen.entrySet().removeIf(e -> {
            if (e.getValue() < cutoff) {
                buckets.remove(e.getKey());
                return true;
            }
            return false;
        });
    }
}
