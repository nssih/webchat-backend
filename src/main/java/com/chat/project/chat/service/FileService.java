package com.chat.project.chat.service;

import com.chat.project.chat.dto.response.UploadResponse;
import com.chat.project.chat.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.UUID;

@Service
public class FileService {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/zip",
            "text/plain"
    );
    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_FILE_SIZE  = 20L * 1024 * 1024;

    private final String uploadPath;

    public FileService(@Value("${webchat.upload.path}") String uploadPath) {
        this.uploadPath = uploadPath;
    }

    public UploadResponse uploadImage(MultipartFile file) throws IOException {
        validateFile(file, ALLOWED_IMAGE_TYPES, MAX_IMAGE_SIZE);
        return save(file, "images");
    }

    public UploadResponse uploadFile(MultipartFile file) throws IOException {
        validateFile(file, ALLOWED_FILE_TYPES, MAX_FILE_SIZE);
        return save(file, "files");
    }

    private void validateFile(MultipartFile file, Set<String> allowed, long maxSize) {
        if (file.isEmpty()) throw new BusinessException("文件不能为空");
        String ct = file.getContentType();
        if (ct == null || !allowed.contains(ct)) throw new BusinessException("不支持的文件类型");
        if (file.getSize() > maxSize) throw new BusinessException("文件超过大小限制");
    }

    private UploadResponse save(MultipartFile file, String subdir) throws IOException {
        Path dir = Paths.get(uploadPath, subdir);
        Files.createDirectories(dir);
        String ext = getExtension(file.getOriginalFilename());
        String stored = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        file.transferTo(dir.resolve(stored));
        return UploadResponse.builder()
                .url("/uploads/" + subdir + "/" + stored)
                .filename(stored)
                .originalName(file.getOriginalFilename())
                .size(file.getSize())
                .mimeType(file.getContentType())
                .build();
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i > 0 ? filename.substring(i + 1).toLowerCase() : "";
    }
}