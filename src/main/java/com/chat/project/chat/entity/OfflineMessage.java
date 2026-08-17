package com.chat.project.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "offline_messages", indexes = {
        @Index(name = "idx_offline_to", columnList = "toUsername"),
        @Index(name = "idx_offline_expires", columnList = "expiresAt")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_offline_msg_to", columnNames = {"messageId", "toUsername"})
})
public class OfflineMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String messageId;

    @Column(nullable = false, length = 50)
    private String fromUsername;

    @Column(nullable = false, length = 50)
    private String toUsername;

    // 加密后的消息内容（前端已端对端加密，服务器只是临时中转）
    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 50)
    private String contentType;

    @Column(nullable = false)
    private Long timestamp;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    // 群消息专用字段（私聊时均为 null）
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_key_version")
    private Integer groupKeyVersion;

    // 消息引用字段（无引用时均为 null）
    @Column(name = "reply_to_id", length = 50)
    private String replyToId;

    @Column(name = "reply_to_sender", length = 100)
    private String replyToSender;

    @Column(name = "reply_to_content", columnDefinition = "TEXT")
    private String replyToContent;

    // 记录类型："message"（默认）= 普通离线消息；"receipt" = 状态回执（toUsername 为发送方）
    // nullable=true：允许旧行为 null，代码层把 null 当 "message" 处理，避免 ddl-auto:update 因 NOT NULL 约束失败
    @Column(name = "msg_type", length = 20)
    private String msgType = "message";

    public OfflineMessage() {}

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getFromUsername() { return fromUsername; }
    public void setFromUsername(String fromUsername) { this.fromUsername = fromUsername; }
    public String getToUsername() { return toUsername; }
    public void setToUsername(String toUsername) { this.toUsername = toUsername; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public Integer getGroupKeyVersion() { return groupKeyVersion; }
    public void setGroupKeyVersion(Integer groupKeyVersion) { this.groupKeyVersion = groupKeyVersion; }
    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
    public String getReplyToSender() { return replyToSender; }
    public void setReplyToSender(String replyToSender) { this.replyToSender = replyToSender; }
    public String getReplyToContent() { return replyToContent; }
    public void setReplyToContent(String replyToContent) { this.replyToContent = replyToContent; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
}