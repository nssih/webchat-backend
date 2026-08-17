package com.chat.project.chat.repository;

import com.chat.project.chat.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUid(String uid);
    boolean existsByUsername(String username);
    List<User> findByUsernameIn(List<String> usernames);

    @Query("""
        SELECT u FROM User u
        WHERE u.uid = :exact
           OR LOWER(u.username) LIKE LOWER(CONCAT('%', :kw, '%')) ESCAPE '\\'
           OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :kw, '%')) ESCAPE '\\'
        ORDER BY u.id
        LIMIT 20
        """)
    List<User> searchByKeyword(@Param("kw") String keyword, @Param("exact") String exact);

    // 查找超过指定时间未登录的用户（lastLoginAt 为 null 的视为从未登录，以 createdAt 兜底）
    @Query("SELECT u FROM User u WHERE u.lastLoginAt < :cutoff OR (u.lastLoginAt IS NULL AND u.createdAt < :cutoff)")
    List<User> findInactiveUsers(@Param("cutoff") Instant cutoff);
}