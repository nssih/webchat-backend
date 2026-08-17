package com.chat.project.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UploadGroupKeyRequest {

    @NotBlank
    @Size(max = 8192)
    private String encryptedKey;

    @NotBlank
    @Size(max = 100)
    private String targetUsername;

    @NotBlank
    @Size(max = 100)
    private String wrappedBy;

    // 可选：前端不传时后端自动递增版本号
    private Integer keyVersion;

    public String getEncryptedKey() { return encryptedKey; }
    public void setEncryptedKey(String encryptedKey) { this.encryptedKey = encryptedKey; }
    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
    public String getWrappedBy() { return wrappedBy; }
    public void setWrappedBy(String wrappedBy) { this.wrappedBy = wrappedBy; }
    public Integer getKeyVersion() { return keyVersion; }
    public void setKeyVersion(Integer keyVersion) { this.keyVersion = keyVersion; }
}
