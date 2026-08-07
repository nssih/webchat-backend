package com.chat.project.chat.controller;

import com.chat.project.chat.dto.request.CreateGroupRequest;
import com.chat.project.chat.dto.request.UploadGroupKeyRequest;
import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.GroupResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.GroupService;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsMessage;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupService groupService;
    private final ChatWebSocketHandler wsHandler;

    public GroupController(GroupService groupService, ChatWebSocketHandler wsHandler) {
        this.groupService = groupService;
        this.wsHandler = wsHandler;
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
        // leaveGroup 在事务内完成 DB 操作并返回事件描述
        GroupService.LeaveEvent event = groupService.leaveGroup(groupId, principal.getId());
        // 事务已提交，安全执行 WS 广播（副作用不在事务内）
        switch (event) {
            case GroupService.LeaveEvent.Dissolved d -> {
                WsMessage dissolveMsg = WsMessage.builder()
                        .type(WsMessage.Type.GROUP_DISSOLVED)
                        .groupId(d.groupId())
                        .toGroupName(d.groupName())
                        .timestamp(System.currentTimeMillis())
                        .build();
                for (String username : d.notifyUsernames()) {
                    wsHandler.sendToUsername(username, dissolveMsg);
                }
            }
            case GroupService.LeaveEvent.MemberLeft m -> {
                WsMessage rotateMsg = WsMessage.builder()
                        .type(WsMessage.Type.GROUP_KEY_ROTATE)
                        .groupId(m.groupId())
                        .toGroupName(m.groupName())
                        .timestamp(System.currentTimeMillis())
                        .build();
                for (String username : m.remainingUsernames()) {
                    wsHandler.sendToUsername(username, rotateMsg);
                }
            }
        }
        return ApiResponse.ok("已退出群组", null);
    }

    @PutMapping("/{groupId}/keys")
    public ApiResponse<Void> uploadGroupKey(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @Valid @RequestBody UploadGroupKeyRequest req) {
        groupService.uploadGroupKey(groupId, principal.getId(), req);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{groupId}/keys/me")
    public ApiResponse<String> getMyGroupKey(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId) {
        String username = principal.getUsername();
        return ApiResponse.ok(groupService.getGroupKey(groupId, username));
    }
}