package com.chat.project.chat.repository;

import com.chat.project.chat.entity.GroupKeyHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface GroupKeyHistoryRepository extends JpaRepository<GroupKeyHistory, Long> {

    Optional<GroupKeyHistory> findByGroupIdAndUsernameAndKeyVersion(
            Long groupId, String username, int keyVersion);

    @Modifying
    @Query("DELETE FROM GroupKeyHistory h WHERE h.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM GroupKeyHistory h WHERE h.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") Long groupId);
}
