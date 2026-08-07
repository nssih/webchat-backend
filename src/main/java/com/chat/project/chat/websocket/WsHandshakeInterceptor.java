package com.chat.project.chat.websocket;

import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeInterceptor.class);

    private final JwtUtil jwtUtil;

    public WsHandshakeInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            token = servletRequest.getServletRequest().getParameter("token");
        }
        if (token == null || !jwtUtil.isValid(token) || !jwtUtil.isAccessToken(token)) {
            log.warn("WebSocket 握手失败: token 无效");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Long userId = jwtUtil.getUserId(token);
        String username = jwtUtil.getUsername(token);
        if (username == null) {
            log.warn("WebSocket 握手失败: token 缺少 username claim");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("userId", userId);
        attributes.put("username", username);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {}
}