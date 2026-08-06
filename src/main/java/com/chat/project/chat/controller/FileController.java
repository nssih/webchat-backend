package com.chat.project.chat.controller;

import com.chat.project.chat.dto.response.ApiResponse;
import com.chat.project.chat.dto.response.UploadResponse;
import com.chat.project.chat.service.FileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/upload")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/image")
    public ApiResponse<UploadResponse> uploadImage(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ApiResponse.ok(fileService.uploadImage(file));
    }

    @PostMapping("/file")
    public ApiResponse<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file)
            throws IOException {
        return ApiResponse.ok(fileService.uploadFile(file));
    }
}