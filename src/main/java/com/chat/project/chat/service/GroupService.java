package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.CreateGroupRequest;
import com.chat.project.chat.dto.response.GroupResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.Group;
import com.chat.project.chat.entity.GroupMember;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.repository.GroupMemberRepository;
import com.chat.project.chat.repository.GroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserService userService;

    public GroupService(GroupRepository groupRepository,
                        GroupMemberRepository groupMemberRepository,
                        UserService userService) {
        this.groupRepository = groupRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.userService = userService;
    }

    @Transactional
    public GroupResponse createGroup(Long ownerId, CreateGroupRequest req) {
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
            throw new BusinessException("你不是群成员");
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

    @Transactional
    public void leaveGroup(Long groupId, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new BusinessException("你不是群成员");
        }
        Group group = findById(groupId);
        if (group.getOwner().getId().equals(userId)) {
            groupRepository.delete(group);
        } else {
            groupMemberRepository.deleteByGroupIdAndUserId(groupId, userId);
        }
    }

    public List<GroupResponse> getMyGroups(Long userId) {
        return groupRepository.findByMemberUserId(userId)
                .stream().map(this::toResponse).toList();
    }

    public GroupResponse getGroup(Long groupId, Long userId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new BusinessException("无权查看该群组");
        }
        return toResponse(findById(groupId));
    }

    public boolean isMember(Long groupId, Long userId) {
        return groupMemberRepository.existsByGroupIdAndUserId(groupId, userId);
    }

    public Group findById(Long id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> new BusinessException("群组不存在"));
    }

    private GroupResponse toResponse(Group g) {
        List<UserResponse> members = groupMemberRepository.findByGroupId(g.getId())
                .stream().map(m -> UserResponse.from(m.getUser())).toList();
        return GroupResponse.from(g, UserResponse.from(g.getOwner()), members);
    }
}