package com.chat.project.chat.controller;

import com.chat.project.chat.dto.request.UpdateProfileRequest;
import com.chat.project.chat.dto.request.UpdatePublicKeyRequest;
import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
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