package com.chat.project.chat.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "group_keys",
       uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "username"}))
public class GroupKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false, length = 100)
    private String username;

    // AES-256 群密钥，用该用户的 ECDH 公钥加密后的 Base64 密文
    @Column(columnDefinition = "TEXT", nullable = false)
    private String encryptedKey;

    // 谁包装了这份密钥（即用谁的私钥 + target 公钥做 ECDH）
    @Column(nullable = false, length = 100)
    private String wrappedBy;

    // 密钥版本号，从 1 开始，每次轮换 +1；旧版本存入 group_key_history
    @Column(name = "key_version", nullable = false, columnDefinition = "integer not null default 1")
    private int keyVersion = 1;

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
}
