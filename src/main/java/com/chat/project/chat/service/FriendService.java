package com.chat.project.chat.service;

import com.chat.project.chat.dto.response.FriendResponse;
import com.chat.project.chat.dto.response.UserResponse;
import com.chat.project.chat.entity.Friendship;
import com.chat.project.chat.entity.Friendship.FriendshipStatus;
import com.chat.project.chat.entity.User;
import com.chat.project.chat.exception.BusinessException;
import com.chat.project.chat.repository.FriendshipRepository;
import com.chat.project.chat.websocket.ChatWebSocketHandler;
import com.chat.project.chat.websocket.WsMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FriendService {

    private final FriendshipRepository friendshipRepository;
    private final UserService userService;
    private final ChatWebSocketHandler wsHandler;

    public FriendService(FriendshipRepository friendshipRepository,
                         UserService userService,
                         ChatWebSocketHandler wsHandler) {
        this.friendshipRepository = friendshipRepository;
        this.userService = userService;
        this.wsHandler = wsHandler;
    }

    @Transactional
    public void sendRequest(Long fromId, Long toId) {
        if (fromId.equals(toId)) throw new BusinessException("不能添加自己为好友");

        User from = userService.findById(fromId);
        User to   = userService.findById(toId);    // single load, reused below

        friendshipRepository.findAcceptedFriendship(fromId, toId)
                .ifPresent(f -> { throw new BusinessException("已经是好友关系"); });
        friendshipRepository.findByUserIdAndFriendId(fromId, toId)
                .ifPresent(f -> { throw new BusinessException("已发送过好友申请"); });

        Friendship f = new Friendship();
        f.setUser(from);
        f.setFriend(to);
        f.setStatus(FriendshipStatus.PENDING);
        Friendship saved = friendshipRepository.save(f);

        // Push a real-time notification to the target user if they are online
        wsHandler.sendToUser(toId, WsMessage.builder()
                .type(WsMessage.Type.FRIEND_REQUEST)
                .fromUserId(fromId)
                .fromUsername(from.getUsername())
                .fromNickname(from.getNickname())
                .fromAvatar(from.getAvatar())
                .messageId(String.valueOf(saved.getId()))
                .timestamp(System.currentTimeMillis())
                .build());
    }

    @Transactional
    public void handleRequest(Long currentUserId, Long friendshipId, boolean accept) {
        Friendship f = friendshipRepository.findById(friendshipId)
                .orElseThrow(() -> new BusinessException("好友申请不存在"));
        if (!f.getFriend().getId().equals(currentUserId)) throw new BusinessException("无权操作");
        if (f.getStatus() != FriendshipStatus.PENDING) throw new BusinessException("申请已处理");

        if (accept) {
            f.setStatus(FriendshipStatus.ACCEPTED);
            friendshipRepository.save(f);

            // Only create reverse row if it does not already exist
            boolean reverseExists = friendshipRepository
                    .findByUserIdAndFriendId(f.getFriend().getId(), f.getUser().getId())
                    .isPresent();
            if (!reverseExists) {
                Friendship reverse = new Friendship();
                reverse.setUser(f.getFriend());
                reverse.setFriend(f.getUser());
                reverse.setStatus(FriendshipStatus.ACCEPTED);
                friendshipRepository.save(reverse);
            }

            // Notify the original requester that their request was accepted
            wsHandler.sendToUser(f.getUser().getId(), WsMessage.builder()
                    .type(WsMessage.Type.FRIEND_ACCEPTED)
                    .fromUserId(currentUserId)
                    .fromUsername(f.getFriend().getUsername())
                    .fromNickname(f.getFriend().getNickname())
                    .fromAvatar(f.getFriend().getAvatar())
                    .timestamp(System.currentTimeMillis())
                    .build());
        } else {
            f.setStatus(FriendshipStatus.REJECTED);
            friendshipRepository.save(f);
        }
    }

    @Transactional
    public void deleteFriend(Long userId, Long friendId) {
        friendshipRepository.findAcceptedFriendship(userId, friendId)
                .orElseThrow(() -> new BusinessException("好友关系不存在"));
        friendshipRepository.findByUserIdAndFriendId(userId, friendId)
                .ifPresent(friendshipRepository::delete);
        friendshipRepository.findByUserIdAndFriendId(friendId, userId)
                .ifPresent(friendshipRepository::delete);
    }

    public List<FriendResponse> getFriends(Long userId) {
        return friendshipRepository.findByUserIdAndStatus(userId, FriendshipStatus.ACCEPTED)
                .stream()
                .map(f -> FriendResponse.from(f, UserResponse.from(f.getFriend())))
                .toList();
    }

    public List<FriendResponse> getPendingRequests(Long userId) {
        return friendshipRepository.findByFriendIdAndStatus(userId, FriendshipStatus.PENDING)
                .stream()
                .map(f -> FriendResponse.from(f, UserResponse.from(f.getUser())))
                .toList();
    }

    public boolean areFriends(Long userId, Long otherId) {
        return friendshipRepository.findAcceptedFriendship(userId, otherId).isPresent();
    }
}