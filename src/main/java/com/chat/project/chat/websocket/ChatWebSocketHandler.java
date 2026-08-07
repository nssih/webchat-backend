package com.chat.project.chat.websocket;

import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import com.chat.project.chat.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    // 同一时刻最多 11 个并发文件传输（与原 FileController 保持一致）
    private static final Semaphore TRANSFER_SEMAPHORE = new Semaphore(11);

    // 进行中的传输元数据
    private record TransferMeta(String fromUsername, String toUsername) {}
    // 进行中的传输：transferId → TransferMeta
    private final Map<String, TransferMeta> activeTransfers = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final UserRepository userRepository;

    // 以 username 为 key
    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    // 每个用户最后一次 PING 的时间戳，用于心跳超时判定
    private final Map<String, Long> lastPingTime = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                GroupMemberRepository groupMemberRepository,
                                GroupRepository groupRepository,
                                UserRepository userRepository) {
        this.objectMapper = objectMapper;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String username = getUsername(session);
        sessions.compute(username, (k, list) -> {
            if (list == null) list = new CopyOnWriteArrayList<>();
            list.add(session);
            return list;
        });
        lastPingTime.put(username, System.currentTimeMillis());
        log.info("用户 {} 已连接", username);
        broadcastExcept(username, WsMessage.builder()
                .type(WsMessage.Type.USER_ONLINE)
                .fromUsername(username)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WsMessage msg;
        try {
            msg = objectMapper.readValue(message.getPayload(), WsMessage.class);
        } catch (Exception e) {
            sendError(session, "消息格式错误");
            return;
        }
        switch (msg.getType()) {
            case PING -> {
                lastPingTime.put(getUsername(session), System.currentTimeMillis());
                sendToSession(session, WsMessage.builder()
                        .type(WsMessage.Type.PONG)
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
            case CHAT -> handleChat(session, msg);
            case GROUP_CHAT -> handleGroupChat(session, msg);
            case FILE_TRANSFER_START -> handleFileTransferStart(session, msg);
            case FILE_CHUNK -> handleFileChunk(session, msg);
            case FILE_CHUNK_ACK -> handleFileChunkAck(session, msg);
            case FILE_TRANSFER_END -> handleFileTransferEnd(session, msg);
            case FILE_TRANSFER_ERROR -> handleFileTransferError(session, msg);
            case MESSAGE_READ -> handleMessageRead(session, msg);
            default -> sendError(session, "未知消息类型");
        }
    }

    private void handleChat(WebSocketSession session, WsMessage msg) throws IOException {
        if (msg.getToUsername() == null) { sendError(session, "缺少目标用户"); return; }
        if (msg.getContent() != null && msg.getContent().length() > 10000) {
            sendError(session, "消息内容过长"); return;
        }
        if (!isOnline(msg.getToUsername())) {
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.CHAT_DELIVERY)
                    .messageId(msg.getMessageId())
                    .status("offline")
                    .timestamp(System.currentTimeMillis())
                    .build());
            return;
        }
        WsMessage out = buildOutMessage(msg, session);
        out.setType(WsMessage.Type.NEW_MESSAGE);
        sendToUsername(msg.getToUsername(), out);
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .status("delivered")
                .timestamp(out.getTimestamp())
                .build());
    }

    private void handleGroupChat(WebSocketSession session, WsMessage msg) throws IOException {
        if (msg.getGroupId() == null && msg.getToGroupName() == null) {
            sendError(session, "缺少目标群组"); return;
        }
        if (msg.getContent() != null && msg.getContent().length() > 10000) {
            sendError(session, "消息内容过长"); return;
        }
        String fromUsername = getUsername(session);
        Long groupId;
        if (msg.getGroupId() != null) {
            groupId = msg.getGroupId();
            // 验证群存在（groupId 模式下做一次轻量校验）
            if (!groupRepository.existsById(groupId)) {
                sendError(session, "群组不存在"); return;
            }
        } else {
            Optional<Group> groupOpt = groupRepository.findByName(msg.getToGroupName());
            if (groupOpt.isEmpty()) { sendError(session, "群组不存在"); return; }
            groupId = groupOpt.get().getId();
        }
        Long fromUserId = getUserId(session);
        boolean isMember = groupMemberRepository.existsByGroupIdAndUserId(groupId, fromUserId);
        if (!isMember) { sendError(session, "你不是群成员"); return; }
        WsMessage out = buildOutMessage(msg, session);
        out.setType(WsMessage.Type.NEW_MESSAGE);
        out.setGroupId(groupId);
        List<GroupMember> members = groupMemberRepository.findByGroupId(groupId);
        for (GroupMember m : members) {
            String memberUsername = m.getUser().getUsername();
            if (!memberUsername.equals(fromUsername)) {
                sendToUsername(memberUsername, out);
            }
        }
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .status("delivered")
                .timestamp(out.getTimestamp())
                .build());
    }

    // ===== 分片文件传输 =====

    private void handleFileTransferStart(WebSocketSession session, WsMessage msg) {
        String toUsername = msg.getToUsername();
        String transferId = msg.getTransferId();
        String fromUsername = getUsername(session);
        if (toUsername == null || transferId == null) { sendError(session, "参数不完整"); return; }
        if (!isOnline(toUsername)) {
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(transferId)
                    .content("对方当前不在线，请等对方上线后再发送")
                    .build());
            return;
        }
        if (!TRANSFER_SEMAPHORE.tryAcquire()) {
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(transferId)
                    .content("当前发送的人太多了，请稍等一下再试")
                    .build());
            return;
        }
        activeTransfers.put(transferId, new TransferMeta(fromUsername, toUsername));
        // 把 START 透传给接收方，让接收方弹出接受/拒绝确认
        // 发送方通过等待接收方回传的 FILE_CHUNK_ACK(chunkIndex=-1) 来得知接收方已就绪
        String[] na = getNicknameAndAvatar(fromUsername);
        WsMessage startOut = WsMessage.builder()
                .type(WsMessage.Type.FILE_TRANSFER_START)
                .transferId(transferId)
                .messageId(msg.getMessageId())
                .toUsername(toUsername)
                .fromUsername(fromUsername)
                .fromNickname(na[0])
                .fromAvatar(na[1])
                .filename(msg.getFilename())
                .fileSize(msg.getFileSize())
                .contentType(msg.getContentType())
                .totalChunks(msg.getTotalChunks())
                .timestamp(System.currentTimeMillis())
                .build();
        sendToUsername(toUsername, startOut);
    }

    private void handleFileChunk(WebSocketSession session, WsMessage msg) {
        String transferId = msg.getTransferId();
        if (transferId == null || !activeTransfers.containsKey(transferId)) {
            sendError(session, "无效的传输会话");
            return;
        }
        TransferMeta meta = activeTransfers.get(transferId);
        String toUsername = meta.toUsername();
        // 接收方若已离线，立即终止传输
        if (!isOnline(toUsername)) {
            activeTransfers.remove(transferId);
            TRANSFER_SEMAPHORE.release();
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(transferId)
                    .content("对方已离线，文件发送中断")
                    .build());
            return;
        }
        // 服务器不缓存，直接转发给接收方；ACK 由接收方写盘后自行回传
        WsMessage chunkOut = WsMessage.builder()
                .type(WsMessage.Type.FILE_CHUNK)
                .transferId(transferId)
                .chunkIndex(msg.getChunkIndex())
                .totalChunks(msg.getTotalChunks())
                .fileData(msg.getFileData())
                .build();
        sendToUsername(toUsername, chunkOut);
    }

    private void handleFileChunkAck(WebSocketSession session, WsMessage msg) {
        String transferId = msg.getTransferId();
        if (transferId == null || msg.getChunkIndex() == null) return;
        TransferMeta meta = activeTransfers.get(transferId);
        if (meta == null) return;
        // 接收方写盘后回传 ACK，服务器纯中继给发送方，驱动其发下一片
        sendToUsername(meta.fromUsername(), WsMessage.builder()
                .type(WsMessage.Type.FILE_CHUNK_ACK)
                .transferId(transferId)
                .chunkIndex(msg.getChunkIndex())
                .build());
    }

    private void handleFileTransferEnd(WebSocketSession session, WsMessage msg) {
        String transferId = msg.getTransferId();
        if (transferId == null) return;
        TransferMeta meta = activeTransfers.remove(transferId);
        if (meta == null) {
            sendError(session, "无效的传输会话");
            return;
        }
        TRANSFER_SEMAPHORE.release();
        sendToUsername(meta.toUsername(), WsMessage.builder()
                .type(WsMessage.Type.FILE_TRANSFER_END)
                .transferId(transferId)
                .messageId(msg.getMessageId())
                .timestamp(System.currentTimeMillis())
                .build());
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .status("delivered")
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private void handleFileTransferError(WebSocketSession session, WsMessage msg) {
        String transferId = msg.getTransferId();
        if (transferId == null) return;
        TransferMeta meta = activeTransfers.remove(transferId);
        if (meta != null) {
            TRANSFER_SEMAPHORE.release();
            String senderOfError = getUsername(session);
            // 通知对方（不是自己）传输中断
            String notifyTarget = senderOfError.equals(meta.fromUsername())
                    ? meta.toUsername()    // 发送方取消 → 通知接收方
                    : meta.fromUsername(); // 接收方拒绝 → 通知发送方
            sendToUsername(notifyTarget, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(transferId)
                    .content(senderOfError.equals(meta.fromUsername())
                            ? "对方取消了文件发送"
                            : "对方拒绝了文件接收")
                    .build());
        }
    }

    // 接收方打开聊天页面时发来此消息；服务端验证后将已读回执转发给消息发送方
    // 私聊：验证 sender（toUsername）确实是已注册用户
    // 群聊：验证 reader 是群成员（通过 groupId 字段区分）
    private void handleMessageRead(WebSocketSession session, WsMessage msg) {
        if (msg.getMessageId() == null) return;
        String readerUsername = getUsername(session);
        String senderUsername = msg.getToUsername();
        if (senderUsername == null || senderUsername.equals(readerUsername)) return;

        if (msg.getGroupId() != null) {
            // 群聊：验证 reader 是群成员，防止非成员伪造回执
            Long readerUserId = getUserId(session);
            if (!groupMemberRepository.existsByGroupIdAndUserId(msg.getGroupId(), readerUserId)) {
                return;
            }
        } else {
            // 私聊：验证 sender 是已注册用户，防止向任意不存在的 username 发回执
            if (!userRepository.existsByUsername(senderUsername)) {
                return;
            }
        }

        sendToUsername(senderUsername, WsMessage.builder()
                .type(WsMessage.Type.MESSAGE_READ)
                .messageId(msg.getMessageId())
                .fromUsername(readerUsername)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String username = getUsername(session);
        List<WebSocketSession> userSessions = sessions.get(username);
        if (userSessions != null) {
            userSessions.remove(session);
            if (userSessions.isEmpty()) sessions.remove(username);
        }
        // 清理该用户所有未完成的传输，释放信号量，通知对方
        activeTransfers.entrySet().removeIf(e -> {
            String transferId = e.getKey();
            TransferMeta meta = e.getValue();
            boolean isSender = username.equals(meta.fromUsername());
            boolean isReceiver = username.equals(meta.toUsername());
            if (isSender) {
                sendToUsername(meta.toUsername(), WsMessage.builder()
                        .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                        .transferId(transferId)
                        .content("对方已断线，文件接收中断")
                        .build());
                TRANSFER_SEMAPHORE.release();
                return true;
            }
            if (isReceiver) {
                sendToUsername(meta.fromUsername(), WsMessage.builder()
                        .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                        .transferId(transferId)
                        .content("对方已断线，文件发送中断")
                        .build());
                TRANSFER_SEMAPHORE.release();
                return true;
            }
            return false;
        });
        log.info("用户 {} 断开连接", username);
        if (!isOnline(username)) {
            lastPingTime.remove(username);
            broadcastExcept(username, WsMessage.builder()
                    .type(WsMessage.Type.USER_OFFLINE)
                    .fromUsername(username)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
    }

    // 每 30 秒扫描一次，关闭超过 90 秒没有 PING 的 session
    // 90s = 25s×3次 + 15s 容忍，确保连续 3 次心跳无响应后强制离线
    @Scheduled(fixedDelay = 30000)
    public void evictStaleSessions() {
        long now = System.currentTimeMillis();
        final long TIMEOUT = 90_000L;
        for (String username : List.copyOf(sessions.keySet())) {
            Long last = lastPingTime.get(username);
            if (last == null || now - last <= TIMEOUT) continue;
            List<WebSocketSession> ss = sessions.get(username);
            if (ss == null) continue;
            for (WebSocketSession s : List.copyOf(ss)) {
                try { s.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: {}", exception.getMessage());
    }

    public void sendToUsername(String username, WsMessage msg) {
        List<WebSocketSession> userSessions = sessions.get(username);
        if (userSessions == null) return;
        userSessions.forEach(s -> sendToSession(s, msg));
    }

    // 推给接收方（NEW_MESSAGE），同时给发送方回一条 CHAT_DELIVERY 确认
    public void sendToUsernameWithSender(String toUsername, String fromUsername, WsMessage msg) {
        String[] na = getNicknameAndAvatar(fromUsername);
        WsMessage toReceiver = WsMessage.builder()
                .type(WsMessage.Type.NEW_MESSAGE)
                .messageId(msg.getMessageId())
                .toUsername(msg.getToUsername())
                .fromUsername(msg.getFromUsername())
                .fromNickname(na[0])
                .fromAvatar(na[1])
                .contentType(msg.getContentType())
                .filename(msg.getFilename())
                .fileSize(msg.getFileSize())
                .fileData(msg.getFileData())
                .timestamp(msg.getTimestamp())
                .build();
        sendToUsername(toUsername, toReceiver);

        sendToUsername(fromUsername, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .timestamp(msg.getTimestamp())
                .build());
    }

    public boolean isOnline(String username) {
        List<WebSocketSession> s = sessions.get(username);
        return s != null && !s.isEmpty();
    }

    public List<String> getOnlineUsernames() {
        return sessions.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
    }

    private void broadcastExcept(String excludeUsername, WsMessage msg) {
        sessions.keySet().stream()
                .filter(u -> !u.equals(excludeUsername))
                .forEach(u -> sendToUsername(u, msg));
    }

    private void sendToSession(WebSocketSession session, WsMessage msg) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(msg);
                // StandardWebSocketSession.sendMessage 不是线程安全的，需要同步
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                    }
                }
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

    private WsMessage buildOutMessage(WsMessage msg, WebSocketSession session) {
        Map<String, Object> attrs = session.getAttributes();
        String fromUsername = (String) attrs.get("username");
        String[] na = getNicknameAndAvatar(fromUsername);
        return WsMessage.builder()
                .type(msg.getType())
                .messageId(msg.getMessageId())
                .toUsername(msg.getToUsername())
                .toGroupName(msg.getToGroupName())
                .contentType(msg.getContentType())
                .content(msg.getContent())
                .filename(msg.getFilename())
                .fileSize(msg.getFileSize())
                .fromUsername(fromUsername)
                .fromNickname(na[0])
                .fromAvatar(na[1])
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private String getUsername(WebSocketSession session) {
        return (String) session.getAttributes().get("username");
    }

    private Long getUserId(WebSocketSession session) {
        return (Long) session.getAttributes().get("userId");
    }

    private String getNickname(String username) {
        return userRepository.findByUsername(username)
                .map(u -> u.getNickname())
                .orElse(null);
    }

    private String getAvatar(String username) {
        return userRepository.findByUsername(username)
                .map(u -> u.getAvatar())
                .orElse(null);
    }

    private String[] getNicknameAndAvatar(String username) {
        return userRepository.findByUsername(username)
                .map(u -> new String[]{ u.getNickname(), u.getAvatar() })
                .orElse(new String[]{ null, null });
    }
}