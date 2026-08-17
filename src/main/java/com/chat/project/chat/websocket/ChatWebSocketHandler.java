package com.chat.project.chat.websocket;

import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.entity.GroupKey;
import com.chat.project.chat.entity.OfflineMessage;
import com.chat.project.chat.repository.GroupKeyRepository;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import com.chat.project.chat.repository.OfflineMessageRepository;
import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.service.OfflineMessageDeliveryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    // 进行中的传输元数据
    private record TransferMeta(String fromUsername, String toUsername) {}
    // 进行中的传输：transferId → TransferMeta
    private final Map<String, TransferMeta> activeTransfers = new ConcurrentHashMap<>();
    // 跟踪正在传输中的用户对 (from,to)，防止同一对话并发传输
    // key 格式 "from<to"，双向检查（A→B 和 B→A 互斥）
    private final Set<String> activeTransferPairs = ConcurrentHashMap.newKeySet();
    // 主动离开的用户（刷新/关闭页面发送 PAGE_UNLOAD），断线后应清锁而非等续传
    private final Set<String> intentionalLeave = ConcurrentHashMap.newKeySet();
    // 全局消息序号，初始化为当前时间戳，防止 Render 重启后 seq 回绕导致前端排序错乱
    private final AtomicLong messageSeq = new AtomicLong(System.currentTimeMillis());

    private static final long OFFLINE_TTL_HOURS = 72;

    private final ObjectMapper objectMapper;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final GroupKeyRepository groupKeyRepository;
    private final UserRepository userRepository;
    private final OfflineMessageRepository offlineMessageRepository;
    private final OfflineMessageDeliveryService offlineMessageDeliveryService;
    private final TaskScheduler taskScheduler;

    // per-username WS 消息速率限制：1 秒窗口，最多 20 条（PING/FILE_CHUNK 豁免）
    private final ConcurrentHashMap<String, long[]> msgBuckets = new ConcurrentHashMap<>();

    private boolean isWsMsgLimited(String username) {
        long now = System.currentTimeMillis();
        long[] b = msgBuckets.computeIfAbsent(username, k -> new long[]{0L, now});
        synchronized (b) {
            if (now - b[1] >= 1000L) { b[0] = 1L; b[1] = now; return false; }
            return ++b[0] > 20;
        }
    }

    // 以 username 为 key
    private final Map<String, List<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    // 每个用户最后一次 PING 的时间戳，用于心跳超时判定
    private final Map<String, Long> lastPingTime = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ObjectMapper objectMapper,
                                GroupMemberRepository groupMemberRepository,
                                GroupRepository groupRepository,
                                GroupKeyRepository groupKeyRepository,
                                UserRepository userRepository,
                                OfflineMessageRepository offlineMessageRepository,
                                OfflineMessageDeliveryService offlineMessageDeliveryService,
                                TaskScheduler taskScheduler) {
        this.objectMapper = objectMapper;
        this.groupMemberRepository = groupMemberRepository;
        this.groupRepository = groupRepository;
        this.groupKeyRepository = groupKeyRepository;
        this.userRepository = userRepository;
        this.offlineMessageRepository = offlineMessageRepository;
        this.offlineMessageDeliveryService = offlineMessageDeliveryService;
        this.taskScheduler = taskScheduler;
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
        // intentionalLeave 里如果还有残留（PAGE_UNLOAD 后重连），清掉即可；传输锁已在 afterConnectionClosed 里处理
        intentionalLeave.remove(username);
        log.info("用户 {} 已连接", username);
        broadcastExcept(username, WsMessage.builder()
                .type(WsMessage.Type.USER_ONLINE)
                .fromUsername(username)
                .timestamp(System.currentTimeMillis())
                .build());

        // 通过独立 @Transactional Service 原子性地读取并删除离线消息，
        // 避免 WebSocket 基础设施直接调用此方法导致 @Transactional 代理被绕过
        List<OfflineMessage> pending = offlineMessageDeliveryService.fetchAndDeleteOfflineMessages(username);
        if (!pending.isEmpty()) {
            // 批量预查群名，避免 N+1（只查有 groupId 的消息对应的群）
            Set<Long> groupIds = pending.stream()
                    .filter(om -> om.getGroupId() != null)
                    .map(OfflineMessage::getGroupId)
                    .collect(Collectors.toSet());
            Map<Long, String> groupNameMap = groupIds.isEmpty() ? Map.of() :
                    groupRepository.findAllById(groupIds).stream()
                            .collect(Collectors.toMap(Group::getId, Group::getName));

            for (OfflineMessage om : pending) {
                String[] na = getNicknameAndAvatar(om.getFromUsername());
                WsMessage.Builder builder = WsMessage.builder()
                        .type(WsMessage.Type.NEW_MESSAGE)
                        .messageId(om.getMessageId())
                        .fromUsername(om.getFromUsername())
                        .fromNickname(na[0])
                        .fromAvatar(na[1])
                        .contentType(om.getContentType())
                        .content(om.getContent())
                        .timestamp(om.getTimestamp());
                if (om.getGroupId() != null) {
                    // 群离线消息：携带 groupId、群名、密钥版本号
                    String groupName = groupNameMap.getOrDefault(om.getGroupId(), "已解散的群组");
                    builder.groupId(om.getGroupId())
                            .toGroupName(groupName)
                            .keyVersion(om.getGroupKeyVersion());
                } else {
                    builder.toUsername(username);
                }
                // 透传引用字段
                if (om.getReplyToId() != null) {
                    builder.replyToId(om.getReplyToId())
                            .replyToSender(om.getReplyToSender())
                            .replyToContent(om.getReplyToContent());
                }
                sendToSession(session, builder.build());
                // 通知发送方（如在线）离线消息已送达接收方
                sendToUsername(om.getFromUsername(), WsMessage.builder()
                        .type(WsMessage.Type.CHAT_DELIVERY)
                        .messageId(om.getMessageId())
                        .status("received")
                        .timestamp(System.currentTimeMillis())
                        .build());
            }
            log.info("已向用户 {} 投递 {} 条离线消息", username, pending.size());
        }

        // 若群主离线期间有成员退群，上线后补发 GROUP_KEY_ROTATE 触发密钥轮换
        List<Group> pendingRotation = groupRepository.findPendingRotationByOwnerUsername(username);
        for (com.chat.project.chat.entity.Group g : pendingRotation) {
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.GROUP_KEY_ROTATE)
                    .groupId(g.getId())
                    .toGroupName(g.getName())
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("向群主 {} 补发 GROUP_KEY_ROTATE，群：{}", username, g.getName());
        }
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
            case FILE_CHUNK, FILE_CHUNK_ACK -> {
                // 文件分片豁免速率限制（传输期间分片数量大）
                if (msg.getType() == WsMessage.Type.FILE_CHUNK) handleFileChunk(session, msg);
                else handleFileChunkAck(session, msg);
            }
            default -> {
                // 其余消息类型受速率限制
                if (isWsMsgLimited(getUsername(session))) {
                    sendError(session, "消息发送过于频繁，请稍候");
                    return;
                }
                switch (msg.getType()) {
                    case CHAT -> handleChat(session, msg);
                    case GROUP_CHAT -> handleGroupChat(session, msg);
                    case FILE_TRANSFER_START -> handleFileTransferStart(session, msg);
                    case FILE_TRANSFER_END -> handleFileTransferEnd(session, msg);
                    case FILE_TRANSFER_ERROR -> handleFileTransferError(session, msg);
                    case FILE_SAVED -> handleFileSaved(session, msg);
                    case MESSAGE_READ -> handleMessageRead(session, msg);
                    case MESSAGE_RECEIVED -> handleMessageReceived(session, msg);
                    case PAGE_UNLOAD -> {
                        intentionalLeave.add(getUsername(session));
                        // 同步从 sessions 移除并广播下线，不等 afterConnectionClosed 异步触发
                        // 这样后续其他用户发来的消息立刻走离线存储，不会丢失
                        afterConnectionClosed(session, CloseStatus.NORMAL);
                        try { session.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
                    }
                    default -> sendError(session, "未知消息类型");
                }
            }
        }
    }

    private void handleChat(WebSocketSession session, WsMessage msg) throws IOException {
        if (msg.getToUsername() == null) { sendError(session, "缺少目标用户"); return; }
        if (msg.getContent() != null && msg.getContent().length() > 500_000) {
            sendError(session, "消息内容过长"); return;
        }
        if (!isActivelyOnline(msg.getToUsername())) {
            String ct = msg.getContentType();
            if (ct != null && !ct.equals("text")) {
                sendToSession(session, WsMessage.builder()
                        .type(WsMessage.Type.CHAT_DELIVERY)
                        .messageId(msg.getMessageId())
                        .status("offline")
                        .timestamp(System.currentTimeMillis())
                        .build());
                return;
            }
            // 存储加密文本消息，等对方上线后投递（幂等：重传时跳过已存在的记录）
            Instant now = Instant.now();
            if (!offlineMessageRepository.existsByMessageIdAndToUsername(msg.getMessageId(), msg.getToUsername())) {
                OfflineMessage om = new OfflineMessage();
                om.setMessageId(msg.getMessageId());
                om.setFromUsername(getUsername(session));
                om.setToUsername(msg.getToUsername());
                om.setContent(msg.getContent());
                om.setContentType(ct != null ? ct : "text");
                om.setTimestamp(System.currentTimeMillis());
                om.setCreatedAt(now);
                om.setExpiresAt(now.plusSeconds(OFFLINE_TTL_HOURS * 3600));
                om.setReplyToId(msg.getReplyToId());
                om.setReplyToSender(msg.getReplyToSender());
                om.setReplyToContent(msg.getReplyToContent());
                offlineMessageRepository.save(om);
            }
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
        boolean delivered = sendToUsername(msg.getToUsername(), out);
        if (!delivered) {
            // session 已关闭（对方刚断线），转为离线存储
            String ct = msg.getContentType() != null ? msg.getContentType() : "text";
            if ("text".equals(ct) && !offlineMessageRepository.existsByMessageIdAndToUsername(msg.getMessageId(), msg.getToUsername())) {
                Instant now = Instant.now();
                OfflineMessage om = new OfflineMessage();
                om.setMessageId(msg.getMessageId());
                om.setFromUsername(getUsername(session));
                om.setToUsername(msg.getToUsername());
                om.setContent(msg.getContent());
                om.setContentType(ct);
                om.setTimestamp(System.currentTimeMillis());
                om.setCreatedAt(now);
                om.setExpiresAt(now.plusSeconds(OFFLINE_TTL_HOURS * 3600));
                om.setReplyToId(msg.getReplyToId());
                om.setReplyToSender(msg.getReplyToSender());
                om.setReplyToContent(msg.getReplyToContent());
                offlineMessageRepository.save(om);
            }
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.CHAT_DELIVERY)
                    .messageId(msg.getMessageId())
                    .status("text".equals(msg.getContentType()) ? "offline" : "failed")
                    .timestamp(System.currentTimeMillis())
                    .build());
            return;
        }
        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .status("sent")
                .timestamp(out.getTimestamp())
                .build());
    }

    private void handleGroupChat(WebSocketSession session, WsMessage msg) throws IOException {
        if (msg.getGroupId() == null && msg.getToGroupName() == null) {
            sendError(session, "缺少目标群组"); return;
        }
        if (msg.getContent() != null && msg.getContent().length() > 500_000) {
            sendError(session, "消息内容过长"); return;
        }
        String fromUsername = getUsername(session);
        Long groupId;
        if (msg.getGroupId() != null) {
            groupId = msg.getGroupId();
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
        List<String> onlineMembers = new ArrayList<>();
        List<String> offlineMembers = new ArrayList<>();
        for (GroupMember m : members) {
            String memberUsername = m.getUser().getUsername();
            if (memberUsername.equals(fromUsername)) continue;
            if (isActivelyOnline(memberUsername)) onlineMembers.add(memberUsername);
            else offlineMembers.add(memberUsername);
        }

        boolean anyDelivered = false;
        if (!onlineMembers.isEmpty()) {
            // 有在线成员：正常发送
            for (String u : onlineMembers) {
                sendToUsername(u, out);
            }
            anyDelivered = true;
        }
        // 离线成员独立判断：不论是否有人在线，只要有离线成员且是文字消息，均存离线消息
        if (!offlineMembers.isEmpty() && "text".equals(msg.getContentType())) {
            int keyVersion = groupKeyRepository
                    .findByGroupIdAndUsername(groupId, fromUsername)
                    .map(GroupKey::getKeyVersion)
                    .orElse(1);
            Instant now = Instant.now();
            for (String offlineUser : offlineMembers) {
                // 幂等检查：messageId 保持原始值，toUsername 区分不同收件人，组合唯一
                if (offlineMessageRepository.existsByMessageIdAndToUsername(msg.getMessageId(), offlineUser)) continue;
                OfflineMessage om = new OfflineMessage();
                om.setMessageId(msg.getMessageId());   // 保持原始 messageId，不拼接用户名
                om.setFromUsername(fromUsername);
                om.setToUsername(offlineUser);
                om.setGroupId(groupId);
                om.setGroupKeyVersion(keyVersion);
                om.setContent(msg.getContent());
                om.setContentType("text");
                om.setTimestamp(System.currentTimeMillis());
                om.setCreatedAt(now);
                om.setExpiresAt(now.plusSeconds(OFFLINE_TTL_HOURS * 3600));
                om.setReplyToId(msg.getReplyToId());
                om.setReplyToSender(msg.getReplyToSender());
                om.setReplyToContent(msg.getReplyToContent());
                offlineMessageRepository.save(om);
            }
        }

        sendToSession(session, WsMessage.builder()
                .type(WsMessage.Type.CHAT_DELIVERY)
                .messageId(msg.getMessageId())
                .status(anyDelivered ? "sent" : "offline")
                .timestamp(out.getTimestamp())
                .build());
    }

    // ===== 分片文件传输 =====

    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024; // 2 GB

    private void handleFileTransferStart(WebSocketSession session, WsMessage msg) {
        String toUsername = msg.getToUsername();
        String transferId = msg.getTransferId();
        String fromUsername = getUsername(session);
        if (toUsername == null || transferId == null) { sendError(session, "参数不完整"); return; }

        // 文件大小校验
        if (msg.getFileSize() != null) {
            if (msg.getFileSize() < 0) { sendError(session, "无效的文件大小"); return; }
            if (msg.getFileSize() > MAX_FILE_SIZE) {
                sendToSession(session, WsMessage.builder()
                        .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                        .transferId(transferId)
                        .content("文件大小超过 2GB 限制")
                        .build());
                return;
            }
        }
        // 文件名校验
        if (msg.getFilename() != null) {
            String fname = msg.getFilename();
            if (fname.length() > 255) { sendError(session, "文件名过长"); return; }
            if (fname.contains("/") || fname.contains("\\") || fname.contains("..")) {
                sendError(session, "文件名包含非法字符"); return;
            }
        }

        if (!isOnline(toUsername)) {
            sendToSession(session, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(transferId)
                    .content("对方当前不在线，请等对方上线后再发送")
                    .build());
            return;
        }
        // 检查同一对话是否已有传输（A→B 和 B→A 互斥）
        String pairKey = pairKey(fromUsername, toUsername);
        if (!activeTransferPairs.add(pairKey)) {
            // 传输已存在：可能是发送方断线重连后重发的 START
            // 检查 transferId 是否匹配，匹配则重传 START 给接收方（让接收方重新弹窗或忽略）
            TransferMeta existing = activeTransfers.get(transferId);
            if (existing != null && fromUsername.equals(existing.fromUsername()) && toUsername.equals(existing.toUsername())) {
                // 同一传输重试：重新转发 START 给接收方
                String[] na = getNicknameAndAvatar(fromUsername);
                sendToUsername(toUsername, WsMessage.builder()
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
                        .build());
            } else {
                sendToSession(session, WsMessage.builder()
                        .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                        .transferId(transferId)
                        .content("你与对方之间已有文件传输，请等待完成后再次发送")
                        .build());
            }
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
        // 接收方暂时离线（断线重连中）：保留传输，等接收方上线后自动恢复
        // 仍转发 chunk，如果接收方不在线则消息丢失，发送方会因 ACK 超时重试
        if (isOnline(toUsername)) {
            sendToUsername(toUsername, WsMessage.builder()
                    .type(WsMessage.Type.FILE_CHUNK)
                    .transferId(transferId)
                    .chunkIndex(msg.getChunkIndex())
                    .totalChunks(msg.getTotalChunks())
                    .fileData(msg.getFileData())
                    .build());
        }
        // 接收方离线时不做清理，发送方稍后会因 waitForChunkAck 超时重试该 chunk
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
        cleanTransferPair(meta);
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
            cleanTransferPair(meta);
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

    // 接收方保存文件后发来此消息；服务端纯中继转发给发送方
    private void handleFileSaved(WebSocketSession session, WsMessage msg) {
        String transferId = msg.getTransferId();
        String toUsername = msg.getToUsername(); // 接收方在消息中指明了发送方
        if (transferId == null || toUsername == null) return;
        sendToUsername(toUsername, WsMessage.builder()
                .type(WsMessage.Type.FILE_SAVED)
                .transferId(transferId)
                .messageId(msg.getMessageId())
                .build());
    }

    // 接收方 WS 收到 NEW_MESSAGE 后立即回此消息；服务端转发给发送方，升级消息为 received（双灰勾）
    private void handleMessageReceived(WebSocketSession session, WsMessage msg) {
        if (msg.getMessageId() == null) return;
        String receiverUsername = getUsername(session);
        String senderUsername = msg.getToUsername();
        if (senderUsername == null || senderUsername.equals(receiverUsername)) return;
        sendToUsername(senderUsername, WsMessage.builder()
                .type(WsMessage.Type.MESSAGE_RECEIVED)
                .messageId(msg.getMessageId())
                .fromUsername(receiverUsername)
                .timestamp(System.currentTimeMillis())
                .build());
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
        // 使用 compute 原子性地 remove session，避免 TOCTOU 竞态（快速断线重连时新 session 可能被误删）
        final boolean[] shouldCleanup = {false};
        sessions.compute(username, (k, list) -> {
            if (list == null) return null;
            list.remove(session);
            if (list.isEmpty()) {
                shouldCleanup[0] = true;
                return null;
            }
            return list;
        });
        if (shouldCleanup[0]) {
            lastPingTime.remove(username);
            msgBuckets.remove(username);  // 防止内存泄漏
            boolean hasTransfer = activeTransfers.values().stream()
                    .anyMatch(meta -> username.equals(meta.fromUsername()) || username.equals(meta.toUsername()));
            if (intentionalLeave.remove(username) && hasTransfer) {
                cancelTransfersForUser(username);
            } else if (hasTransfer) {
                taskScheduler.schedule(() -> {
                    if (!isOnline(username)) {
                        cancelTransfersForUser(username);
                    }
                }, Instant.now().plus(Duration.ofMinutes(5)));
            }
            broadcastExcept(username, WsMessage.builder()
                    .type(WsMessage.Type.USER_OFFLINE)
                    .fromUsername(username)
                    .timestamp(System.currentTimeMillis())
                    .build());
        }
        log.info("用户 {} 断开连接", username);
    }

    // 每 30 秒扫描一次，关闭超过 90 秒没有 PING 的 session
    // 90s = 25s×3次 + 15s 容忍，确保连续 3 次心跳无响应后强制离线
    // 但如果有活跃的文件传输（该用户是发送方或接收方），则跳过超时判定，
    // 因为传输期间浏览器后台标签页的 setInterval 可能被 Chrome 降频到约 1 分钟一次，
    // 且接收方弹系统对话框选保存位置也可能阻塞心跳，导致服务器误踢。
    // 但如果传输用户超过 5 分钟（300 秒）没有 PING，同样判定离线，清理传输防止僵尸连接
    @Scheduled(fixedDelay = 30000)
    public void evictStaleSessions() {
        long now = System.currentTimeMillis();
        final long TIMEOUT = 90_000L;
        final long TRANSFER_TIMEOUT = 300_000L;  // 传输中用户 5 分钟无心跳才判定离线
        for (String username : List.copyOf(sessions.keySet())) {
            Long last = lastPingTime.get(username);
            if (last == null) continue;
            long elapsed = now - last;
            boolean hasTransfer = hasActiveTransfer(username);
            if (hasTransfer && elapsed <= TRANSFER_TIMEOUT) continue;  // 传输中且在宽限期内
            if (!hasTransfer && elapsed <= TIMEOUT) continue;         // 非传输中正常判定
            // 超时：关闭连接并清理传输
            if (hasTransfer) {
                cancelTransfersForUser(username);
            }
            List<WebSocketSession> ss = sessions.get(username);
            if (ss == null) continue;
            for (WebSocketSession s : List.copyOf(ss)) {
                try { s.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) {}
            }
        }
    }

    /** 检查指定用户是否有进行中的文件传输（作为发送方或接收方） */
    private boolean hasActiveTransfer(String username) {
        return activeTransfers.values().stream()
                .anyMatch(meta -> username.equals(meta.fromUsername()) || username.equals(meta.toUsername()));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: {}", exception.getMessage());
    }

    public boolean sendToUsername(String username, WsMessage msg) {
        List<WebSocketSession> userSessions = sessions.get(username);
        if (userSessions == null) return false;
        boolean sent = false;
        for (WebSocketSession s : userSessions) {
            if (sendToSession(s, msg)) sent = true;
        }
        return sent;
    }

    // 强制关闭指定用户的所有 WS session（注销账号时调用）
    public void forceDisconnect(String username) {
        List<WebSocketSession> userSessions = sessions.get(username);
        if (userSessions == null) return;
        for (WebSocketSession s : List.copyOf(userSessions)) {
            try { s.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
        }
    }

    public boolean isOnline(String username) {
        List<WebSocketSession> s = sessions.get(username);
        return s != null && !s.isEmpty();
    }

    // 判断用户是否"活跃在线"：session 存在且 45 秒内有过心跳
    // 用于消息发送路径，防止手机后台冻结导致消息假发送（session.isOpen() 为 true 但对方已无响应）
    private boolean isActivelyOnline(String username) {
        if (!isOnline(username)) return false;
        Long last = lastPingTime.get(username);
        return last != null && (System.currentTimeMillis() - last) <= 45_000L;
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

    private boolean sendToSession(WebSocketSession session, WsMessage msg) {
        try {
            if (session.isOpen()) {
                String json = objectMapper.writeValueAsString(msg);
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
        return false;
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
                .seq(messageSeq.incrementAndGet())
                .replyToId(msg.getReplyToId())
                .replyToSender(msg.getReplyToSender())
                .replyToContent(msg.getReplyToContent())
                .build();
    }

    private String getUsername(WebSocketSession session) {
        return (String) session.getAttributes().get("username");
    }

    private Long getUserId(WebSocketSession session) {
        return (Long) session.getAttributes().get("userId");
    }

    private String[] getNicknameAndAvatar(String username) {
        return userRepository.findByUsername(username)
                .map(u -> new String[]{ u.getNickname(), u.getAvatar() })
                .orElse(new String[]{ null, null });
    }

    /** 生成用户对 key，双向互斥：pairKey(A,B) == pairKey(B,A) */
    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "<" + b : b + "<" + a;
    }

    /** 传输结束时清理对应的用户对 */
    private void cleanTransferPair(TransferMeta meta) {
        activeTransferPairs.remove(pairKey(meta.fromUsername(), meta.toUsername()));
    }

    /** 清理该用户涉及的所有传输，并通知传输对方 */
    private void cancelTransfersForUser(String username) {
        activeTransfers.entrySet().removeIf(e -> {
            TransferMeta meta = e.getValue();
            if (!username.equals(meta.fromUsername()) && !username.equals(meta.toUsername())) return false;
            activeTransferPairs.remove(pairKey(meta.fromUsername(), meta.toUsername()));
            String peer = username.equals(meta.fromUsername()) ? meta.toUsername() : meta.fromUsername();
            sendToUsername(peer, WsMessage.builder()
                    .type(WsMessage.Type.FILE_TRANSFER_ERROR)
                    .transferId(e.getKey())
                    .content("对方已断开连接，传输已终止")
                    .timestamp(System.currentTimeMillis())
                    .build());
            return true;
        });
    }
}