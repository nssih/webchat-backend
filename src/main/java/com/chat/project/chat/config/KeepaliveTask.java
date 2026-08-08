package com.chat.project.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 定时保活任务，防止 Render 免费服务器因 15 分钟无流量而休眠。
 * 每 10 分钟对自己发一个轻量 HTTP 请求，确保服务器始终保持活跃。
 * 这样用户无需等待冷启动（约 1 分钟），文件传输也不会因休眠中断。
 */
@Component
public class KeepaliveTask {

    private static final Logger log = LoggerFactory.getLogger(KeepaliveTask.class);

    private final HttpClient httpClient;
    private final String selfUrl;

    public KeepaliveTask(@Value("${server.port:8080}") int port) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.selfUrl = "http://localhost:" + port + "/api/auth/me";
    }

    /**
     * 每 10 分钟执行一次，发 HEAD 请求到自身 health 端点。
     * HEAD 请求没有响应体，极其轻量，不影响性能。
     */
    @Scheduled(fixedRate = 600_000)
    public void keepAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(selfUrl))
                    .timeout(Duration.ofSeconds(10))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            log.debug("Keepalive ping sent, status={}", response.statusCode());
        } catch (Exception e) {
            log.debug("Keepalive ping failed (expected during startup/shutdown): {}", e.getMessage());
        }
    }
}
