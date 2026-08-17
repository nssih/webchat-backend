package com.chat.project.chat.repository;

import com.chat.project.chat.entity.OfflineMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OfflineMessageRepository extends JpaRepository<OfflineMessage, Long> {

    List<OfflineMessage> findByToUsernameOrderByTimestampAsc(String toUsername);

    boolean existsByMessageIdAndToUsername(String messageId, String toUsername);

    @Modifying
    @Query("DELETE FROM OfflineMessage m WHERE m.toUsername = :toUsername")
    void deleteByToUsername(@Param("toUsername") String toUsername);

    @Modifying
    @Query("DELETE FROM OfflineMessage m WHERE m.fromUsername = :fromUsername")
    void deleteByFromUsername(@Param("fromUsername") String fromUsername);

    @Query("SELECT m FROM OfflineMessage m WHERE m.expiresAt <= :now")
    List<OfflineMessage> findExpired(@Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM OfflineMessage m WHERE m.expiresAt <= :now")
    void deleteExpired(@Param("now") Instant now);
}