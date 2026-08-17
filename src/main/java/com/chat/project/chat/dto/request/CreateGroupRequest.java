package com.chat.project.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateGroupRequest {

    @NotBlank
    @Size(min = 1, max = 100)
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}