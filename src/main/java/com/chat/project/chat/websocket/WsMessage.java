package com.chat.project.chat.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsMessage {

    public enum Type {
        CHAT, GROUP_CHAT, PING,
        CHAT_DELIVERY, NEW_MESSAGE, PONG, ERROR,
        USER_ONLINE, USER_OFFLINE, GROUP_KEY_ROTATE,
        GROUP_DISSOLVED,
        FILE_TRANSFER_START, FILE_CHUNK, FILE_CHUNK_ACK, FILE_TRANSFER_END, FILE_TRANSFER_ERROR, FILE_SAVED,
        MESSAGE_READ, MESSAGE_RECEIVED, PAGE_UNLOAD,
        GROUP_MEMBER_ADDED
    }

    private Type type;
    private String messageId;
    private String status;   // CHAT_DELIVERY 时：delivered / offline
    private String toUsername;
    private String toGroupName;
    private Long groupId;
    private String contentType;
    private String content;
    private String filename;
    private Long fileSize;
    private String fileData;
    private String fromUsername;
    private String fromNickname;
    private String fromAvatar;
    private Long timestamp;
    private Long seq;
    // 分片传输专用
    private String transferId;
    private Integer chunkIndex;
    private Integer totalChunks;
    // GROUP_MEMBER_ADDED 专用：JSON 序列化的 GroupResponse
    private String groupData;
    // 离线群消息投递时携带，表示加密该消息时使用的密钥版本号
    private Integer keyVersion;
    // 消息引用：被引用消息的 ID、发送者昵称/用户名、内容摘要（前端填入，原样透传）
    private String replyToId;
    private String replyToSender;
    private String replyToContent;

    public WsMessage() {}

    private WsMessage(Builder b) {
        this.type = b.type;
        this.messageId = b.messageId;
        this.status = b.status;
        this.toUsername = b.toUsername;
        this.toGroupName = b.toGroupName;
        this.groupId = b.groupId;
        this.contentType = b.contentType;
        this.content = b.content;
        this.filename = b.filename;
        this.fileSize = b.fileSize;
        this.fileData = b.fileData;
        this.fromUsername = b.fromUsername;
        this.fromNickname = b.fromNickname;
        this.fromAvatar = b.fromAvatar;
        this.timestamp = b.timestamp;
        this.seq = b.seq;
        this.transferId = b.transferId;
        this.chunkIndex = b.chunkIndex;
        this.totalChunks = b.totalChunks;
        this.groupData = b.groupData;
        this.keyVersion = b.keyVersion;
        this.replyToId = b.replyToId;
        this.replyToSender = b.replyToSender;
        this.replyToContent = b.replyToContent;
    }

    public static Builder builder() { return new Builder(); }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getToUsername() { return toUsername; }
    public void setToUsername(String toUsername) { this.toUsername = toUsername; }
    public String getToGroupName() { return toGroupName; }
    public void setToGroupName(String toGroupName) { this.toGroupName = toGroupName; }
    public Long getGroupId() { return groupId; }
    public void setGroupId(Long groupId) { this.groupId = groupId; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileData() { return fileData; }
    public void setFileData(String fileData) { this.fileData = fileData; }
    public String getFromUsername() { return fromUsername; }
    public void setFromUsername(String fromUsername) { this.fromUsername = fromUsername; }
    public String getFromNickname() { return fromNickname; }
    public void setFromNickname(String fromNickname) { this.fromNickname = fromNickname; }
    public String getFromAvatar() { return fromAvatar; }
    public void setFromAvatar(String fromAvatar) { this.fromAvatar = fromAvatar; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public String getTransferId() { return transferId; }
    public void setTransferId(String transferId) { this.transferId = transferId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public String getGroupData() { return groupData; }
    public void setGroupData(String groupData) { this.groupData = groupData; }
    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }
    public String getReplyToId() { return replyToId; }
    public void setReplyToId(String replyToId) { this.replyToId = replyToId; }
    public String getReplyToSender() { return replyToSender; }
    public void setReplyToSender(String replyToSender) { this.replyToSender = replyToSender; }
    public String getReplyToContent() { return replyToContent; }
    public void setReplyToContent(String replyToContent) { this.replyToContent = replyToContent; }

    public static class Builder {
        private Type type;
        private String messageId;
        private String status;
        private String toUsername;
        private String toGroupName;
        private Long groupId;
        private String contentType;
        private String content;
        private String filename;
        private Long fileSize;
        private String fileData;
        private String fromUsername;
        private String fromNickname;
        private String fromAvatar;
        private Long timestamp;
        private Long seq;
        private String transferId;
        private Integer chunkIndex;
        private Integer totalChunks;
        private String groupData;
        private Integer keyVersion;
        private String replyToId;
        private String replyToSender;
        private String replyToContent;

        public Builder type(Type v) { this.type = v; return this; }
        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder toUsername(String v) { this.toUsername = v; return this; }
        public Builder toGroupName(String v) { this.toGroupName = v; return this; }
        public Builder groupId(Long v) { this.groupId = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder filename(String v) { this.filename = v; return this; }
        public Builder fileSize(Long v) { this.fileSize = v; return this; }
        public Builder fileData(String v) { this.fileData = v; return this; }
        public Builder fromUsername(String v) { this.fromUsername = v; return this; }
        public Builder fromNickname(String v) { this.fromNickname = v; return this; }
        public Builder fromAvatar(String v) { this.fromAvatar = v; return this; }
        public Builder timestamp(Long v) { this.timestamp = v; return this; }
        public Builder seq(Long v) { this.seq = v; return this; }
        public Builder transferId(String v) { this.transferId = v; return this; }
        public Builder chunkIndex(Integer v) { this.chunkIndex = v; return this; }
        public Builder totalChunks(Integer v) { this.totalChunks = v; return this; }
        public Builder groupData(String v) { this.groupData = v; return this; }
        public Builder keyVersion(Integer v) { this.keyVersion = v; return this; }
        public Builder replyToId(String v) { this.replyToId = v; return this; }
        public Builder replyToSender(String v) { this.replyToSender = v; return this; }
        public Builder replyToContent(String v) { this.replyToContent = v; return this; }
        public WsMessage build() { return new WsMessage(this); }
    }
}