package com.chat.project.chat.controller;

import com.chat.project.chat.dto.request.CreateGroupRequest;
import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.GroupResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    public ApiResponse<GroupResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateGroupRequest req) {
        return ApiResponse.ok(groupService.createGroup(principal.getId(), req));
    }

    @GetMapping
    public ApiResponse<List<GroupResponse>> myGroups(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(groupService.getMyGroups(principal.getId()));
    }

    @GetMapping("/{groupId}")
    public ApiResponse<GroupResponse> getGroup(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId) {
        return ApiResponse.ok(groupService.getGroup(groupId, principal.getId()));
    }

    @PostMapping("/{groupId}/members/{userId}")
    public ApiResponse<GroupResponse> invite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @PathVariable Long userId) {
        return ApiResponse.ok(groupService.inviteMember(groupId, principal.getId(), userId));
    }

    @DeleteMapping("/{groupId}/members/me")
    public ApiResponse<Void> leave(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId) {
        groupService.leaveGroup(groupId, principal.getId());
        return ApiResponse.ok("已退出群组", null);
    }
}