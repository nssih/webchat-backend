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

    // 每个分片 64KB，AES-GCM 加密后约 88KB，Base64 后约 117KB，远小于此限制
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(256 * 1024);          // 256KB，足够单片
        container.setMaxBinaryMessageBufferSize(256 * 1024);
        container.setMaxSessionIdleTimeout(60 * 60 * 1000L);       // 1h idle timeout
        return container;
    }
}