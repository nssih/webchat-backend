package com.chat.project.chat.dto.request;

import jakarta.validation.constraints.NotBlank;

public class DeleteAccountRequest {

    @NotBlank(message = "密码不能为空")
    private String password;

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
