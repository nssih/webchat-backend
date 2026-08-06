package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.LoginRequest;
import com.chat.project.chat.dto.request.RefreshTokenRequest;
import com.chat.project.chat.dto.request.RegisterRequest;
import com.chat.project.chat.dto.response.AuthResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.DeviceToken;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.repository.DeviceTokenRepository;
import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.util.JwtUtil;
import com.chat.project.chat.util.UidGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UidGenerator uidGenerator;
    private final long refreshTokenExpiry;

    public AuthService(UserRepository userRepository,
                       DeviceTokenRepository deviceTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       UidGenerator uidGenerator,
                       @Value("${webchat.jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.userRepository = userRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.uidGenerator = uidGenerator;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUid(generateUniqueUid());
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getUsername());
        userRepository.save(user);
        return issueTokens(user, null, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.getLogin())
                .or(() -> userRepository.findByUid(req.getLogin()))
                .orElseThrow(() -> new BusinessException("用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        return issueTokens(user, req.getDeviceName(), req.getDeviceId());
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest req) {
        DeviceToken dt = deviceTokenRepository.findByRefreshToken(req.getRefreshToken())
                .orElseThrow(() -> new BusinessException("无效的 refresh token"));
        if (dt.getExpiresAt().isBefore(Instant.now())) {
            deviceTokenRepository.delete(dt);
            throw new BusinessException("refresh token 已过期，请重新登录");
        }
        User user = dt.getUser();
        String newAccess = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        dt.setLastUsedAt(Instant.now());
        deviceTokenRepository.save(dt);
        return AuthResponse.builder()
                .accessToken(newAccess)
                .refreshToken(dt.getRefreshToken())
                .user(UserResponse.from(user))
                .build();
    }

    @Transactional
    public void logout(String refreshToken) {
        deviceTokenRepository.deleteByRefreshToken(refreshToken);
    }

    private AuthResponse issueTokens(User user, String deviceName, String deviceId) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        DeviceToken dt = new DeviceToken();
        dt.setUser(user);
        dt.setRefreshToken(refreshToken);
        dt.setDeviceName(deviceName);
        dt.setDeviceId(deviceId);
        dt.setExpiresAt(Instant.now().plusMillis(refreshTokenExpiry));
        dt.setLastUsedAt(Instant.now());
        deviceTokenRepository.save(dt);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .user(UserResponse.from(user))
                .build();
    }

    private String generateUniqueUid() {
        String uid;
        do {
            uid = uidGenerator.generate();
        } while (userRepository.findByUid(uid).isPresent());
        return uid;
    }
}