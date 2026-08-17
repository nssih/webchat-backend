package com.chat.project.chat.websocket;

import com.chat.project.chat.service.WsTicketService;
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
    private final WsTicketService wsTicketService;

    public WsHandshakeInterceptor(JwtUtil jwtUtil, WsTicketService wsTicketService) {
        this.jwtUtil = jwtUtil;
        this.wsTicketService = wsTicketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        var req = servletRequest.getServletRequest();

        // 优先使用一次性 ticket（避免 token 出现在 URL 日志）
        String ticket = req.getParameter("ticket");
        if (ticket != null) {
            var wsTicket = wsTicketService.consume(ticket);
            if (wsTicket.isEmpty()) {
                log.warn("WebSocket 握手失败: ticket 无效或已过期");
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("userId", wsTicket.get().userId());
            attributes.put("username", wsTicket.get().username());
            return true;
        }

        // 降级：兼容旧 ?token= 方式
        String token = req.getParameter("token");
        if (token == null || !jwtUtil.isValid(token) || !jwtUtil.isAccessToken(token)
                || jwtUtil.isRevoked(token)) {
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