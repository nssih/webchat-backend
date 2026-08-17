package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.CreateGroupRequest;
import com.chat.project.chat.dto.request.UploadGroupKeyRequest;
import com.chat.project.chat.dto.response.GroupResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.entity.GroupKey;
import com.chat.project.chat.entity.GroupKeyHistory;
import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.exception.ForbiddenException;
import com.chat.project.chat.exception.NotFoundException;
import com.chat.project.chat.repository.GroupKeyHistoryRepository;
import com.chat.project.chat.repository.GroupKeyRepository;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupService {

    // 事务提交后需要广播的 WS 事件描述，由 Controller 层执行广播
    public sealed interface LeaveEvent permits LeaveEvent.Dissolved, LeaveEvent.MemberLeft {
        record Dissolved(Long groupId, String groupName, List<String> notifyUsernames) implements LeaveEvent {}
        record MemberLeft(Long groupId, String groupName, String ownerUsername, List<String> remainingUsernames) implements LeaveEvent {}
    }

    // inviteMember 返回值：携带被邀请人 username 供 Controller 发 WS 通知
    public record InviteResult(GroupResponse group, String invitedUsername) {}

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupKeyRepository groupKeyRepository;
    private final GroupKeyHistoryRepository groupKeyHistoryRepository;
    private final UserService userService;

    // 旧版本密钥保留时长：比离线消息 TTL（72h）多 24h，确保离线消息存在期间密钥一定可用
    private static final long HISTORY_TTL_HOURS = 96;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository groupMemberRepository,
                        GroupKeyRepository groupKeyRepository,
                        GroupKeyHistoryRepository groupKeyHistoryRepository,
                        UserService userService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupKeyRepository = groupKeyRepository;
        this.groupKeyHistoryRepository = groupKeyHistoryRepository;
        this.userService = userService;
    }

    @Transactional
    public GroupResponse createGroup(Long ownerId, CreateGroupRequest req) {
        if (groupRepository.findByName(req.getName()).isPresent()) {
            throw new BusinessException("群组名称已被使用，请换一个名字");
        }
        User owner = userService.findById(ownerId);
        Group group = new Group();
        group.setName(req.getName());
        group.setOwner(owner);
        group = groupRepository.save(group);

        GroupMember member = new GroupMember();
        member.setGroup(group);
        member.setUser(owner);
        groupMemberRepository.save(member);
        return toResponse(group);
    }

    @Transactional
    public InviteResult inviteMember(Long groupId, Long inviterId, Long targetUserId) {
        Group group = findById(groupId);
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, inviterId)) {
            throw new ForbiddenException("你不是群成员");
        }
        if (!group.getOwner().getId().equals(inviterId)) {
            throw new ForbiddenException("只有群主才能邀请成员");
        }
        if (groupMemberRepository.existsByGroupIdAndUserId(groupId, targetUserId)) {
            throw new BusinessException("该用户已在群组中");
        }
        User target = userService.findById(targetUserId);
        GroupMember member = new GroupMember();
        member.setGroup(group);
        member.setUser(target);
        groupMemberRepository.save(member);
        // 用已在事务中加载的 group 实体构建响应，避免重新 findById 导致 lazy owner proxy 未初始化
        return new InviteResult(toResponse(group), target.getUsername());
    }

    // 返回需要广播的事件描述，由 Controller 在事务提交后执行广播
    @Transactional
    public LeaveEvent leaveGroup(Long groupId, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("你不是群成员");
        }
        Group group = findById(groupId);
        String leavingUsername = userService.findById(userId).getUsername();

        if (group.getOwner().getId().equals(userId)) {
            // 群主退出 = 解散群：先收集需要通知的成员列表，再删数据
            List<String> notifyUsernames = groupMemberRepository.findByGroupId(groupId).stream()
                    .map(m -> m.getUser().getUsername())
                    .filter(u -> !u.equals(leavingUsername))
                    .toList();
            groupKeyRepository.deleteByGroupId(groupId);
            groupKeyHistoryRepository.deleteByGroupId(groupId);
            groupRepository.delete(group);
            return new LeaveEvent.Dissolved(groupId, group.getName(), notifyUsernames);
        } else {
            // 普通成员退出：删除该成员及其群密钥，收集剩余成员列表
            groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId);
            groupKeyRepository.deleteByGroupIdAndUsername(groupId, leavingUsername);
            // 无论群主是否在线，都标记需要密钥轮换，确保群主下次上线时也能处理
            group.setNeedsKeyRotation(true);
            groupRepository.save(group);
            List<String> remainingUsernames = groupMemberRepository.findByGroupId(groupId).stream()
                    .map(m -> m.getUser().getUsername())
                    .toList();
            return new LeaveEvent.MemberLeft(groupId, group.getName(), group.getOwner().getUsername(), remainingUsernames);
        }
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups(Long userId) {
        List<Group> groups = groupRepository.findByMemberUserId(userId);
        if (groups.isEmpty()) return List.of();
        List<Long> groupIds = groups.stream().map(Group::getId).toList();
        // 一次性批量拉取所有群的成员，避免 N+1
        Map<Long, List<GroupMember>> membersByGroup = groupMemberRepository
                .findByGroupIdIn(groupIds).stream()
                .collect(Collectors.groupingBy(m -> m.getGroup().getId()));
        return groups.stream()
                .map(g -> toResponse(g, membersByGroup.getOrDefault(g.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(Long groupId, Long userId) {
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("群组不存在"));
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("无权查看该群组");
        }
        return toResponse(group);
    }

    public boolean isMember(Long groupId, Long userId) {
        return groupMemberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public Group findById(Long id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("群组不存在"));
    }

    // 上传某个成员的群密钥（调用方已用该成员公钥加密过）
    @Transactional
    public void uploadGroupKey(Long groupId, Long uploaderId, UploadGroupKeyRequest req) {
        Group group = findById(groupId);
        if (!group.getOwner().getId().equals(uploaderId)) {
            throw new ForbiddenException("只有群主才能上传群密钥");
        }
        if (!groupMemberRepository.existsByGroupIdAndUsername(groupId, req.getTargetUsername())) {
            throw new ForbiddenException("目标用户不是该群成员");
        }
        GroupKey existing = groupKeyRepository
                .findByGroupIdAndUsername(groupId, req.getTargetUsername())
                .orElse(null);

        // 计算新版本号：前端若显式传入则使用，否则自动递增
        int newVersion;
        if (existing == null) {
            newVersion = req.getKeyVersion() != null ? req.getKeyVersion() : 1;
        } else if (req.getKeyVersion() != null && req.getKeyVersion() > existing.getKeyVersion()) {
            newVersion = req.getKeyVersion();
        } else if (req.getKeyVersion() != null && req.getKeyVersion().equals(existing.getKeyVersion())) {
            // 同版本重传（网络重试），直接覆盖，不存 history
            newVersion = existing.getKeyVersion();
        } else {
            // 前端未传版本或传入版本不大于现有版本 → 自动递增（轮换场景）
            newVersion = existing.getKeyVersion() + 1;
        }

        // 若确实是版本递增（轮换），把旧密钥存入 history 供离线消息解密使用
        if (existing != null && newVersion > existing.getKeyVersion()) {
            GroupKeyHistory hist = new GroupKeyHistory();
            hist.setGroupId(groupId);
            hist.setUsername(existing.getUsername());
            hist.setEncryptedKey(existing.getEncryptedKey());
            hist.setWrappedBy(existing.getWrappedBy());
            hist.setKeyVersion(existing.getKeyVersion());
            hist.setExpiresAt(Instant.now().plusSeconds(HISTORY_TTL_HOURS * 3600));
            groupKeyHistoryRepository.save(hist);
        }

        GroupKey key = existing != null ? existing : new GroupKey();
        key.setGroupId(groupId);
        key.setUsername(req.getTargetUsername());
        key.setEncryptedKey(req.getEncryptedKey());
        key.setWrappedBy(req.getWrappedBy());
        key.setKeyVersion(newVersion);
        groupKeyRepository.save(key);

        // 仅当全部成员的新版本密钥都已上传完毕时，才清除待轮换标记
        // 防止群主自己那份先上传就提前清除，导致中途下线后无法补发 GROUP_KEY_ROTATE
        if (group.isNeedsKeyRotation()) {
            long uploaded = groupKeyRepository.countByGroupIdAndKeyVersion(groupId, newVersion);
            long total = groupMemberRepository.countByGroupId(groupId);
            if (uploaded >= total) {
                group.setNeedsKeyRotation(false);
                groupRepository.save(group);
            }
        }
    }

    // 返回 encryptedKey|wrappedBy 格式，前端据此解包（最新版本）
    public String getGroupKey(Long groupId, String username) {
        return groupKeyRepository.findByGroupIdAndUsername(groupId, username)
                .map(k -> k.getEncryptedKey() + "|" + k.getWrappedBy())
                .orElse(null);
    }

    // 按版本号查询：先查主表，再查历史表（供离线消息解密使用）
    public String getGroupKey(Long groupId, String username, int version) {
        return groupKeyRepository.findByGroupIdAndUsernameAndKeyVersion(groupId, username, version)
                .map(k -> k.getEncryptedKey() + "|" + k.getWrappedBy())
                .or(() -> groupKeyHistoryRepository
                        .findByGroupIdAndUsernameAndKeyVersion(groupId, username, version)
                        .map(h -> h.getEncryptedKey() + "|" + h.getWrappedBy()))
                .orElse(null);
    }

    private GroupResponse toResponse(Group g) {
        List<UserResponse> members = groupMemberRepository.findByGroupId(g.getId())
                .stream().map(m -> UserResponse.from(m.getUser())).toList();
        return GroupResponse.from(g, UserResponse.from(g.getOwner()), members);
    }

    private GroupResponse toResponse(Group g, List<GroupMember> preloaded) {
        List<UserResponse> members = preloaded.stream()
                .map(m -> UserResponse.from(m.getUser())).toList();
        return GroupResponse.from(g, UserResponse.from(g.getOwner()), members);
    }
}