package com.chat.project.chat.controller;

import com.chat.project.chat.dto.request.LoginRequest;
import com.chat.project.chat.dto.request.RefreshTokenRequest;
import com.chat.project.chat.dto.request.RegisterRequest;
import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.AuthResponse;
import com.chat.project.chat.security.UserPrincipal;
import com.chat.project.chat.service.AuthService;
import com.chat.project.chat.service.WsTicketService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final WsTicketService wsTicketService;

    public AuthController(AuthService authService, WsTicketService wsTicketService) {
        this.authService = authService;
        this.wsTicketService = wsTicketService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return ApiResponse.ok(authService.refresh(req));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @Valid @RequestBody RefreshTokenRequest req,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String accessToken = null;
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            accessToken = authHeader.substring(7);
        }
        authService.logout(req.getRefreshToken(), accessToken);
        return ApiResponse.ok("已退出登录", null);
    }

    /** 签发一次性 WS ticket，避免 token 出现在 WebSocket URL 日志中 */
    @PostMapping("/ws-ticket")
    public ApiResponse<Map<String, String>> wsTicket(@AuthenticationPrincipal UserPrincipal principal) {
        String ticket = wsTicketService.issue(principal.getId(), principal.getUsername());
        return ApiResponse.ok(Map.of("ticket", ticket));
    }
}