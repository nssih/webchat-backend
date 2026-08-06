package com.chat.project.chat.repository;

import com.chat.project.chat.entity.Friendship;
import com.chat.project.chat.entity.Friendship.FriendshipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    @Query("SELECT f FROM Friendship f WHERE f.user.id = :userId AND f.status = :status")
    List<Friendship> findByUserIdAndStatus(@Param("userId") Long userId,
                                           @Param("status") FriendshipStatus status);

    @Query("SELECT f FROM Friendship f WHERE f.friend.id = :friendId AND f.status = :status")
    List<Friendship> findByFriendIdAndStatus(@Param("friendId") Long friendId,
                                              @Param("status") FriendshipStatus status);

    @Query("SELECT f FROM Friendship f WHERE f.user.id = :userId AND f.friend.id = :friendId")
    Optional<Friendship> findByUserIdAndFriendId(@Param("userId") Long userId,
                                                  @Param("friendId") Long friendId);

    @Query("""
        SELECT f FROM Friendship f
        WHERE ((f.user.id = :userId AND f.friend.id = :friendId)
            OR (f.user.id = :friendId AND f.friend.id = :userId))
        AND f.status = 'ACCEPTED'
        """)
    Optional<Friendship> findAcceptedFriendship(@Param("userId") Long userId,
                                                 @Param("friendId") Long friendId);
}