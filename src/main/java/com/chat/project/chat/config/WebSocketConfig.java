package com.chat.project.chat.config;

import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(ChatWebSocketHandler chatHandler,
                           WsHandshakeInterceptor handshakeInterceptor) {
        this.chatHandler = chatHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/ws/chat")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    // 每个分片 360KB，Base64 后约 493KB，远小于此限制（留 3% 余量给 JSON 框架）
    // 增大 buffer 可支持更大分片，减少分片数，提升大文件传输速度
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(512 * 1024);          // 512KB，足够单片 360KB×1.37≈493KB
        container.setMaxBinaryMessageBufferSize(512 * 1024);
        container.setMaxSessionIdleTimeout(60 * 60 * 1000L);       // 1h idle timeout
        return container;
    }
}