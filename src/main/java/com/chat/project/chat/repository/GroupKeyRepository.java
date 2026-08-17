package com.chat.project.chat.repository;

import com.chat.project.chat.entity.GroupKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupKeyRepository extends JpaRepository<GroupKey, Long> {
    Optional<GroupKey> findByGroupIdAndUsername(Long groupId, String username);
    Optional<GroupKey> findByGroupIdAndUsernameAndKeyVersion(Long groupId, String username, int keyVersion);
    void deleteByGroupIdAndUsername(Long groupId, String username);
    void deleteByGroupId(Long groupId);
    long countByGroupIdAndKeyVersion(Long groupId, int keyVersion);
}
