package com.chat.project.chat.controller;

import com.chat.project.chat.dto.request.DeleteAccountRequest;
import com.chat.project.chat.dto.request.UpdateProfileRequest;
import com.chat.project.chat.dto.request.UpdatePublicKeyRequest;
import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.GroupService;
import com.chat.project.chat.service.UserService;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsMessage;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final ChatWebSocketHandler wsHandler;

    public UserController(UserService userService, ChatWebSocketHandler wsHandler) {
        this.userService = userService;
        this.wsHandler = wsHandler;
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(userService.getById(principal.getId()));
    }

    @PatchMapping("/me")
    public ApiResponse<UserResponse> updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest req) {
        return ApiResponse.ok(userService.updateProfile(principal.getId(), req));
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> deleteAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = principal.getUsername();
        String accessToken = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }
        List<GroupService.LeaveEvent> events = userService.deleteAccount(
                principal.getId(), accessToken, req.getPassword());
        // 事务已提交，执行 WS 广播
        for (GroupService.LeaveEvent event : events) {
            switch (event) {
                case GroupService.LeaveEvent.Dissolved d -> {
                    WsMessage msg = WsMessage.builder()
                            .type(WsMessage.Type.GROUP_DISSOLVED)
                            .groupId(d.groupId())
                            .toGroupName(d.groupName())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    for (String u : d.notifyUsernames()) {
                        wsHandler.sendToUsername(u, msg);
                    }
                }
                case GroupService.LeaveEvent.MemberLeft m -> {
                    WsMessage msg = WsMessage.builder()
                            .type(WsMessage.Type.GROUP_KEY_ROTATE)
                            .groupId(m.groupId())
                            .toGroupName(m.groupName())
                            .timestamp(System.currentTimeMillis())
                            .build();
                    for (String u : m.remainingUsernames()) {
                        wsHandler.sendToUsername(u, msg);
                    }
                }
            }
        }
        // 强制断开该用户的 WS 连接（afterConnectionClosed 会广播 USER_OFFLINE）
        wsHandler.forceDisconnect(username);
        return ApiResponse.ok("账号已注销", null);
    }

    @GetMapping("/search")
    public ApiResponse<List<UserResponse>> search(@RequestParam String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        return ApiResponse.ok(userService.search(keyword));
    }

    @GetMapping("/online")
    public ApiResponse<List<UserResponse>> online(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(userService.getOnlineUsers(principal.getUsername()));
    }

    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getUser(@PathVariable Long id) {
        return ApiResponse.ok(userService.getById(id));
    }

    @PutMapping("/me/public-key")
    public ApiResponse<Void> updatePublicKey(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdatePublicKeyRequest req) {
        userService.updatePublicKey(principal.getId(), req);
        return ApiResponse.ok(null);
    }

    @GetMapping("/by-username/{username}")
    public ApiResponse<UserResponse> getByUsername(@PathVariable String username) {
        return ApiResponse.ok(userService.getByUsername(username));
    }
}