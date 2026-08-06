package com.chat.project.chat.websocket;

import com.chat.project.chat.entity.User;
import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final UserRepository userRepository;

    public WsHandshakeInterceptor(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
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
            return false;
        }
        Long userId = jwtUtil.getUserId(token);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("WebSocket 握手失败: 用户不存在 {}", userId);
            return false;
        }
        attributes.put("userId", userId);
        attributes.put("username", user.getUsername());
        attributes.put("nickname", user.getNickname());
        attributes.put("avatar", user.getAvatar());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {}
}