package com.chat.project.chat.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WsTicketService {

    public record WsTicket(Long userId, String username, Instant expiresAt) {}

    private final Map<String, WsTicket> tickets = new ConcurrentHashMap<>();

    /** 签发一次性 ticket，30 秒内有效 */
    public String issue(Long userId, String username) {
        String ticket = UUID.randomUUID().toString();
        tickets.put(ticket, new WsTicket(userId, username, Instant.now().plusSeconds(30)));
        return ticket;
    }

    /** 消费 ticket（取出即删，一次性） */
    public Optional<WsTicket> consume(String ticket) {
        WsTicket t = tickets.remove(ticket);
        if (t == null || t.expiresAt().isBefore(Instant.now())) return Optional.empty();
        return Optional.of(t);
    }

    /** 每分钟清理过期 ticket */
    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        Instant now = Instant.now();
        tickets.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }
}
