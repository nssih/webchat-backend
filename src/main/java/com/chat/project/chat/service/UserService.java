package com.chat.project.chat.service;

import com.chat.project.chat.dto.request.UpdateProfileRequest;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = findById(userId);
        if (req.getNickname() != null) user.setNickname(req.getNickname());
        if (req.getAvatar() != null) user.setAvatar(req.getAvatar());
        return UserResponse.from(userRepository.save(user));
    }

    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }
}