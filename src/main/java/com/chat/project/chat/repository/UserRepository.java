package com.chat.project.chat.repository;

import com.chat.project.chat.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByUid(String uid);
    boolean existsByUsername(String username);

    @Query("""
        SELECT u FROM User u
        WHERE u.uid = :exact
           OR LOWER(u.username) LIKE LOWER(CONCAT('%', :kw, '%'))
           OR LOWER(u.nickname) LIKE LOWER(CONCAT('%', :kw, '%'))
        ORDER BY u.id
        LIMIT 20
        """)
    List<User> searchByKeyword(@Param("kw") String keyword, @Param("exact") String exact);
}