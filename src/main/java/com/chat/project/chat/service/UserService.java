package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.UpdateProfileRequest;
import com.chat.project.chat.dto.request.UpdatePublicKeyRequest;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.exception.NotFoundException;
import com.chat.project.chat.repository.UserRepository;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final ChatWebSocketHandler wsHandler;

    public UserService(UserRepository userRepository, ChatWebSocketHandler wsHandler) {
        this.userRepository = userRepository;
        this.wsHandler = wsHandler;
    }

    public UserResponse getById(Long id) {
        return UserResponse.from(findById(id));
    }

    public List<UserResponse> search(String keyword) {
        return userRepository.searchByKeyword(keyword.trim(), keyword.trim())
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    public List<UserResponse> getOnlineUsers(String excludeUsername) {
        List<String> onlineUsernames = wsHandler.getOnlineUsernames().stream()
                .filter(un -> !un.equals(excludeUsername))
                .toList();
        if (onlineUsernames.isEmpty()) return List.of();
        return userRepository.findByUsernameIn(onlineUsernames)
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = findById(userId);
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar());
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void updatePublicKey(Long userId, UpdatePublicKeyRequest req) {
        User user = findById(userId);
        user.setPublicKey(req.getPublicKey());
        userRepository.save(user);
    }

    public UserResponse getByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }
}