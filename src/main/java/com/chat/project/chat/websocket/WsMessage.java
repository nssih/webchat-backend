package com.chat.project.chat.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    public enum Type {
        CHAT, GROUP_CHAT, PING,
        CHAT_DELIVERY, NEW_MESSAGE, PONG, ERROR,
        FRIEND_REQUEST, FRIEND_ACCEPTED,
        USER_ONLINE, USER_OFFLINE
    }

    private Type type;
    private String messageId;
    private Long toUserId;
    private Long toGroupId;
    private String contentType;
    private String content;
    private String filename;
    private Long fileSize;
    private Long fromUserId;
    private String fromUsername;
    private String fromNickname;
    private String fromAvatar;
    private Long timestamp;

    public WsMessage() {}

    private WsMessage(Builder b) {
        this.type = b.type;
        this.messageId = b.messageId;
        this.toUserId = b.toUserId;
        this.toGroupId = b.toGroupId;
        this.contentType = b.contentType;
        this.content = b.content;
        this.filename = b.filename;
        this.fileSize = b.fileSize;
        this.fromUserId = b.fromUserId;
        this.fromUsername = b.fromUsername;
        this.fromNickname = b.fromNickname;
        this.fromAvatar = b.fromAvatar;
        this.timestamp = b.timestamp;
    }

    public static Builder builder() { return new Builder(); }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Long getToUserId() { return toUserId; }
    public void setToUserId(Long toUserId) { this.toUserId = toUserId; }
    public Long getToGroupId() { return toGroupId; }
    public void setToGroupId(Long toGroupId) { this.toGroupId = toGroupId; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Long getFromUserId() { return fromUserId; }
    public void setFromUserId(Long fromUserId) { this.fromUserId = fromUserId; }
    public String getFromUsername() { return fromUsername; }
    public void setFromUsername(String fromUsername) { this.fromUsername = fromUsername; }
    public String getFromNickname() { return fromNickname; }
    public void setFromNickname(String fromNickname) { this.fromNickname = fromNickname; }
    public String getFromAvatar() { return fromAvatar; }
    public void setFromAvatar(String fromAvatar) { this.fromAvatar = fromAvatar; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public static class Builder {
        private Type type;
        private String messageId;
        private Long toUserId;
        private Long toGroupId;
        private String contentType;
        private String content;
        private String filename;
        private Long fileSize;
        private Long fromUserId;
        private String fromUsername;
        private String fromNickname;
        private String fromAvatar;
        private Long timestamp;

        public Builder type(Type v) { this.type = v; return this; }
        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder toUserId(Long v) { this.toUserId = v; return this; }
        public Builder toGroupId(Long v) { this.toGroupId = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder filename(String v) { this.filename = v; return this; }
        public Builder fileSize(Long v) { this.fileSize = v; return this; }
        public Builder fromUserId(Long v) { this.fromUserId = v; return this; }
        public Builder fromUsername(String v) { this.fromUsername = v; return this; }
        public Builder fromNickname(String v) { this.fromNickname = v; return this; }
        public Builder fromAvatar(String v) { this.fromAvatar = v; return this; }
        public Builder timestamp(Long v) { this.timestamp = v; return this; }
        public WsMessage build() { return new WsMessage(this); }
    }
}