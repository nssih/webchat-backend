package com.chat.project.chat.controller;

import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.FriendResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.FriendService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final FriendService friendService;

    public FriendController(FriendService friendService) {
        this.friendService = friendService;
    }

    @PostMapping("/request/{toUserId}")
    public ApiResponse<Void> sendRequest(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long toUserId) {
        friendService.sendRequest(principal.getId(), toUserId);
        return ApiResponse.ok("好友申请已发送", null);
    }

    @PostMapping("/request/{friendshipId}/accept")
    public ApiResponse<Void> accept(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long friendshipId) {
        friendService.handleRequest(principal.getId(), friendshipId, true);
        return ApiResponse.ok("已同意好友申请", null);
    }

    @PostMapping("/request/{friendshipId}/reject")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long friendshipId) {
        friendService.handleRequest(principal.getId(), friendshipId, false);
        return ApiResponse.ok("已拒绝好友申请", null);
    }

    @DeleteMapping("/{friendId}")
    public ApiResponse<Void> deleteFriend(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long friendId) {
        friendService.deleteFriend(principal.getId(), friendId);
        return ApiResponse.ok("已删除好友", null);
    }

    @GetMapping
    public ApiResponse<List<FriendResponse>> getFriends(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(friendService.getFriends(principal.getId()));
    }

    @GetMapping("/requests")
    public ApiResponse<List<FriendResponse>> getPendingRequests(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(friendService.getPendingRequests(principal.getId()));
    }
}