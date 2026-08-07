package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.CreateGroupRequest;
import com.chat.project.chat.dto.request.UploadGroupKeyRequest;
import com.chat.project.chat.dto.response.GroupResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.entity.GroupKey;
import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.exception.ForbiddenException;
import com.chat.project.chat.exception.NotFoundException;
import com.chat.project.chat.repository.GroupKeyRepository;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupService {

    // 事务提交后需要广播的 WS 事件描述，由 Controller 层执行广播
    public sealed interface LeaveEvent permits LeaveEvent.Dissolved, LeaveEvent.MemberLeft {
        record Dissolved(Long groupId, String groupName, List<String> notifyUsernames) implements LeaveEvent {}
        record MemberLeft(Long groupId, String groupName, List<String> remainingUsernames) implements LeaveEvent {}
    }

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupKeyRepository groupKeyRepository;
    private final UserService userService;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository groupMemberRepository,
                        GroupKeyRepository groupKeyRepository,
                        UserService userService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.groupKeyRepository = groupKeyRepository;
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
    public GroupResponse inviteMember(Long groupId, Long inviterId, Long targetUserId) {
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
        return toResponse(groupRepository.findById(groupId).orElseThrow());
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
            groupRepository.delete(group);
            return new LeaveEvent.Dissolved(groupId, group.getName(), notifyUsernames);
        } else {
            // 普通成员退出：删除该成员及其群密钥，收集剩余成员列表
            groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId);
            groupKeyRepository.deleteByGroupIdAndUsername(groupId, leavingUsername);
            List<String> remainingUsernames = groupMemberRepository.findByGroupId(groupId).stream()
                    .map(m -> m.getUser().getUsername())
                    .toList();
            return new LeaveEvent.MemberLeft(groupId, group.getName(), remainingUsernames);
        }
    }

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
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, uploaderId)) {
            throw new ForbiddenException("你不是群成员");
        }
        GroupKey key = groupKeyRepository
                .findByGroupIdAndUsername(groupId, req.getTargetUsername())
                .orElse(new GroupKey());
        key.setGroupId(groupId);
        key.setUsername(req.getTargetUsername());
        key.setEncryptedKey(req.getEncryptedKey());
        key.setWrappedBy(req.getWrappedBy());
        groupKeyRepository.save(key);
    }

    // 返回 encryptedKey:wrappedBy 格式，前端据此解包
    public String getGroupKey(Long groupId, String username) {
        return groupKeyRepository.findByGroupIdAndUsername(groupId, username)
                .map(k -> k.getEncryptedKey() + "|" + k.getWrappedBy())
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