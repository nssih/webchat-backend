package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.UpdateProfileRequest;
import com.chat.project.chat.dto.request.UpdatePublicKeyRequest;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.AuthException;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.exception.NotFoundException;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import com.chat.project.chat.repository.OfflineMessageRepository;
import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.service.GroupService.LeaveEvent;
import com.chat.project.chat.util.JwtUtil;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final ChatWebSocketHandler wsHandler;
    private final GroupService groupService;
    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final OfflineMessageRepository offlineMessageRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       ChatWebSocketHandler wsHandler,
                       @Lazy GroupService groupService,
                       GroupRepository groupRepository,
                       GroupMemberRepository groupMemberRepository,
                       OfflineMessageRepository offlineMessageRepository,
                       JwtUtil jwtUtil,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.wsHandler = wsHandler;
        this.groupService = groupService;
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.offlineMessageRepository = offlineMessageRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse getById(Long id) {
        return UserResponse.from(findById(id));
    }

    public List<UserResponse> search(String keyword) {
        String kw = keyword.trim();
        // 转义 LIKE 元字符，防止 % / _ / \ 被当作通配符枚举用户
        String escaped = kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return userRepository.searchByKeyword(escaped, kw)
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    public List<UserResponse> getOnlineUsers(String excludeUsername) {
        List<String> onlineUsernames = wsHandler.getOnlineUsernames().stream()
                .filter(un -> !un.equals(excludeUsername))
                .toList();
        if (onlineUsernames.isEmpty()) return List.of();
        return userRepository.findByUsernameIn(onlineUsernames)
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = findById(userId);
        if (req.getNickname() != null) {
            String nick = req.getNickname().strip();
            if (nick.chars().anyMatch(c -> c == '<' || c == '>' || c == '"' || c == '\''))
                throw new BusinessException("昵称包含非法字符");
            user.setNickname(nick);
        }
        if (req.getAvatar() != null) {
            String av = req.getAvatar().strip();
            if (!av.isEmpty() && !av.startsWith("https://") && !av.startsWith("data:image/"))
                throw new BusinessException("头像地址协议不合法，仅支持 https:// 或 data:image/");
            user.setAvatar(av);
        }
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void updatePublicKey(Long userId, UpdatePublicKeyRequest req) {
        User user = findById(userId);
        user.setPublicKey(req.getPublicKey());
        userRepository.save(user);
    }

    public UserResponse getByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    // 收集注销时需要广播的群事件（在事务内），返回给 Controller 在事务外广播
    @Transactional
    public List<LeaveEvent> deleteAccount(Long userId, String accessToken) {
        return deleteAccount(userId, accessToken, null);
    }

    @Transactional
    public List<LeaveEvent> deleteAccount(Long userId, String accessToken, String rawPassword) {
        User user = findById(userId);
        String username = user.getUsername();

        // 密码二次确认（提供密码时校验，定时清理任务传 null 跳过）
        if (rawPassword != null && !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new AuthException("密码错误");
        }

        // 收集该用户所在的所有群，逐个退出/解散，收集广播事件
        List<Group> groups = groupRepository.findByMemberUserId(userId);
        List<LeaveEvent> events = new ArrayList<>();
        for (Group group : groups) {
            events.add(groupService.leaveGroup(group.getId(), userId));
        }

        // 删除发给该用户的离线消息（接收方）和该用户发出的离线消息（发送方）
        offlineMessageRepository.deleteByToUsername(username);
        offlineMessageRepository.deleteByFromUsername(username);

        // 删除用户本身（级联删除 DeviceToken）
        userRepository.delete(user);

        // 吊销当前 access token（事务提交后无法再用此 token 访问接口）
        if (accessToken != null) jwtUtil.revokeToken(accessToken);

        return events;
    }

    // 定时任务专用：清理超过指定天数未登录的用户，事务外广播 WS 事件并强制断开连接
    public void purgeInactiveUsers(java.time.Instant cutoff) {
        List<User> inactive = userRepository.findInactiveUsers(cutoff);
        for (User user : inactive) {
            String username = user.getUsername();
            try {
                List<LeaveEvent> events = deleteAccount(user.getId(), null);
                broadcastLeaveEvents(events);
                wsHandler.forceDisconnect(username);
            } catch (Exception e) {
                log.warn("清理用户 {} 失败: {}", username, e.getMessage());
            }
        }
    }

    // 广播群退出/解散的 WS 事件（由 Controller 和定时任务共用）
    public void broadcastLeaveEvents(List<LeaveEvent> events) {
        for (LeaveEvent event : events) {
            switch (event) {
                case LeaveEvent.Dissolved d -> {
                    WsMessage msg = WsMessage.builder()
                            .type(WsMessage.Type.GROUP_DISSOLVED)
                            .groupId(d.groupId())
                            .toGroupName(d.groupName())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    for (String u : d.notifyUsernames()) wsHandler.sendToUsername(u, msg);
                }
                case LeaveEvent.MemberLeft m -> {
                    WsMessage msg = WsMessage.builder()
                            .type(WsMessage.Type.GROUP_KEY_ROTATE)
                            .groupId(m.groupId())
                            .toGroupName(m.groupName())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    for (String u : m.remainingUsernames()) wsHandler.sendToUsername(u, msg);
                }
            }
        }
    }
}