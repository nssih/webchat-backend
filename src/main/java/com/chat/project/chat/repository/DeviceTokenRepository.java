package com.chat.project.chat.repository;

import com.chat.project.chat.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {
    Optional<DeviceToken> findByRefreshToken(String refreshToken);
    void deleteByRefreshToken(String refreshToken);
    void deleteByUserId(Long userId);
    Optional<DeviceToken> findByUserIdAndDeviceId(Long userId, String deviceId);

    @Modifying
    @Query("DELETE FROM DeviceToken t WHERE t.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}