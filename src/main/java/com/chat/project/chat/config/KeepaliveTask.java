package com.chat.project.chat.config;

import com.chat.project.chat.entity.OfflineMessage;
import com.chat.project.chat.repository.DeviceTokenRepository;
import com.chat.project.chat.repository.GroupKeyHistoryRepository;
import com.chat.project.chat.repository.OfflineMessageRepository;
import com.chat.project.chat.service.UserService;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class KeepaliveTask {

    private static final Logger log = LoggerFactory.getLogger(KeepaliveTask.class);

    private final HttpClient httpClient;
    private final String selfUrl;
    private final DeviceTokenRepository deviceTokenRepository;
    private final GroupKeyHistoryRepository groupKeyHistoryRepository;
    private final UserService userService;
    private final OfflineMessageRepository offlineMessageRepository;
    private final ChatWebSocketHandler wsHandler;
    private final AtomicReference<LocalDate> lastPurgeDate = new AtomicReference<>();
    private final AtomicReference<LocalDate> lastTokenPurgeDate = new AtomicReference<>();

    @Lazy
    @Autowired
    private KeepaliveTask self;

    public KeepaliveTask(
            @Value("${server.port:8080}") int port,
            @Value("${SELF_URL:}") String selfUrl,
            DeviceTokenRepository deviceTokenRepository,
            GroupKeyHistoryRepository groupKeyHistoryRepository,
            UserService userService,
            OfflineMessageRepository offlineMessageRepository,
            ChatWebSocketHandler wsHandler) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.deviceTokenRepository = deviceTokenRepository;
        this.groupKeyHistoryRepository = groupKeyHistoryRepository;
        this.userService = userService;
        this.offlineMessageRepository = offlineMessageRepository;
        this.wsHandler = wsHandler;
        if (selfUrl != null && !selfUrl.isBlank()) {
            this.selfUrl = selfUrl + "/actuator/health";
        } else {
            this.selfUrl = "http://localhost:" + port + "/actuator/health";
        }
    }

    // 每小时扫描过期离线消息，通知在线的发送方消息已过期，然后删除（TTL=6小时，需小时级清理）
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void evictExpiredOfflineMessages() {
        Instant now = Instant.now();
        List<OfflineMessage> expired = offlineMessageRepository.findExpired(now);
        if (expired.isEmpty()) return;
        for (OfflineMessage om : expired) {
            wsHandler.sendToUsername(om.getFromUsername(), WsMessage.builder()
                    .type(WsMessage.Type.CHAT_DELIVERY)
                    .messageId(om.getMessageId())
                    .status("expired")
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
        offlineMessageRepository.deleteExpired(now);
        log.info("已清理 {} 条过期离线消息", expired.size());
        // 同步清理过期的历史群密钥（TTL=12h，覆盖离线消息 TTL=6h）
        groupKeyHistoryRepository.deleteExpired(now);
    }

    // 每天凌晨 4 点清理过期的 Refresh Token
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        lastTokenPurgeDate.set(LocalDate.now(ZoneOffset.UTC));
        int deleted = deviceTokenRepository.deleteExpired(Instant.now());
        if (deleted > 0) log.info("已清理 {} 条过期 Refresh Token", deleted);
    }

    // 每天凌晨 2 点清理超过 30 天未登录的用户及其所有关联数据
    @Scheduled(cron = "0 0 2 * * *")
    public void purgeInactiveUsers() {
        lastPurgeDate.set(LocalDate.now(ZoneOffset.UTC));
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(30));
        userService.purgeInactiveUsers(cutoff);
    }

    // 每 10 分钟执行一次，作为 UptimeRobot 的补充保活
    @Scheduled(fixedRate = 600_000)
    public void keepAlive() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(selfUrl))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            log.debug("保活 ping 已发送，状态码={}", response.statusCode());
        } catch (Exception e) {
            log.debug("保活 ping 失败（启动/关闭期间属正常现象）：{}", e.getMessage());
        }

        // 向数据库发一次轻量查询，防止 Neon Serverless 计算节点进入休眠（约 5 分钟无查询即休眠）
        try {
            deviceTokenRepository.count();
        } catch (Exception e) {
            log.debug("数据库保活查询失败：{}", e.getMessage());
        }

        // 补偿清理：仅在 cron 预定时间（UTC 02:00）已过但今天清理尚未执行时才补跑，
        // 防止应用启动时 lastPurgeDate=null 导致与当天 cron 双重执行
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int hourUtc = java.time.ZonedDateTime.now(ZoneOffset.UTC).getHour();
        if (hourUtc >= 2 && !today.equals(lastPurgeDate.get())) {
            lastPurgeDate.set(today);
            Instant cutoff = Instant.now().minus(Duration.ofDays(30));
            userService.purgeInactiveUsers(cutoff);
        }
        if (hourUtc >= 2 && !today.equals(lastTokenPurgeDate.get())) {
            lastTokenPurgeDate.set(today);
            self.purgeExpiredTokens();
        }
    }
}
