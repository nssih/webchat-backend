package com.chat.project.chat.websocket;

import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final GroupMemberRepository groupMemberRepository;
    private final Map<Long, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                 GroupMemberRepository groupMemberRepository) {
        this.objectMapper = objectMapper;
        this.groupMemberRepository = groupMemberRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = getUserId(session);
        sessions.compute(userId, (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(session);
            return list;
        });
        log.info("用户 {} 已连接", userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long fromUserId = getUserId(session);
        WsMessage msg;
        try {
            msg = objectMapper.readValue(message.getPayload(), WsMessage.class);
        } catch (Exception e) {
            sendError(session, "消息格式错误");
            return;
        }
        switch (msg.getType()) {
            case PING -> sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.PONG)
                    .timestamp(System.currentTimeMillis())
                    .build());
            case CHAT -> handleChat(session, fromUserId, msg);
            case GROUP_CHAT -> handleGroupChat(session, fromUserId, msg);
            default -> sendError(session, "未知消息类型");
        }
    }

    private void handleChat(WebSocketSession session, Long fromUserId, WsMessage msg) throws IOException {
        if (msg.getToUserId() == null) { sendError(session, "缺少目标用户"); return; }
        WsMessage out = buildOutMessage(msg, fromUserId, session);
        out.setType(WsMessage.Type.NEW_MESSAGE);
        sendToUser(msg.getToUserId(), out);
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .timestamp(out.getTimestamp())
                .build());
    }

    private void handleGroupChat(WebSocketSession session, Long fromUserId, WsMessage msg) throws IOException {
        if (msg.getToGroupId() == null) { sendError(session, "缺少目标群组"); return; }
        boolean isMember = groupMemberRepository.existsByGroupIdAndUserId(msg.getToGroupId(), fromUserId);
        if (!isMember) { sendError(session, "你不是群成员"); return; }
        WsMessage out = buildOutMessage(msg, fromUserId, session);
        out.setType(WsMessage.Type.NEW_MESSAGE);
        // findByGroupId uses JOIN FETCH so User is eagerly loaded — no LazyInitializationException
        List<GroupMember> members = groupMemberRepository.findByGroupId(msg.getToGroupId());
        for (GroupMember m : members) {
            Long memberId = m.getUser().getId();
            if (!memberId.equals(fromUserId)) {
                sendToUser(memberId, out);
            }
        }
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .timestamp(out.getTimestamp())
                .build());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = getUserId(session);
        List<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions != null) {
            userSessions.remove(session);
            if (userSessions.isEmpty()) sessions.remove(userId);
        }
        log.info("用户 {} 断开连接", userId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: {}", exception.getMessage());
    }

    public void sendToUser(Long userId, WsMessage msg) {
        List<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions == null) return;
        userSessions.forEach(s -> sendToSession(s, msg));
    }

    public boolean isOnline(Long userId) {
        List<WebSocketSession> s = sessions.get(userId);
        return s != null && !s.isEmpty();
    }

    private void sendToSession(WebSocketSession session, WsMessage msg) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            }
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    private void sendError(WebSocketSession session, String error) {
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.ERROR)
                .content(error)
                .build());
    }

    private WsMessage buildOutMessage(WsMessage msg, Long fromUserId, WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        return WsMessage.builder()
                .type(msg.getType())
                .messageId(msg.getMessageId())
                .toUserId(msg.getToUserId())
                .toGroupId(msg.getToGroupId())
                .contentType(msg.getContentType())
                .content(msg.getContent())
                .filename(msg.getFilename())
                .fileSize(msg.getFileSize())
                .fromUserId(fromUserId)
                .fromUsername((String) attrs.get("username"))
                .fromNickname((String) attrs.get("nickname"))
                .fromAvatar((String) attrs.get("avatar"))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private Long getUserId(WebSocketSession session) {
        return (Long) session.getAttributes().get("userId");
    }
}