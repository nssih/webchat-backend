package com.chat.project.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "group_key_history", indexes = {
        @Index(name = "idx_gkh_group_user_ver", columnList = "group_id, username, key_version"),
        @Index(name = "idx_gkh_expires", columnList = "expiresAt")
})
public class GroupKeyHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String encryptedKey;

    @Column(nullable = false, length = 100)
    private String wrappedBy;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @Column(nullable = false)
    private Instant expiresAt;

    public Long getId() { return id; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getEncryptedKey() { return encryptedKey; }
    public void setEncryptedKey(String encryptedKey) { this.encryptedKey = encryptedKey; }
    public String getWrappedBy() { return wrappedBy; }
    public void setWrappedBy(String wrappedBy) { this.wrappedBy = wrappedBy; }
    public int getKeyVersion() { return keyVersion; }
    public void setKeyVersion(int keyVersion) { this.keyVersion = keyVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
