package com.chat.project.chat.dto.response;

public class UploadResponse {
    private String url;
    private String filename;
    private String originalName;
    private long size;
    private String mimeType;

    private UploadResponse() {}

    private UploadResponse(Builder b) {
        this.url = b.url;
        this.filename = b.filename;
        this.originalName = b.originalName;
        this.size = b.size;
        this.mimeType = b.mimeType;
    }

    public static Builder builder() { return new Builder(); }

    public String getUrl() { return url; }
    public String getFilename() { return filename; }
    public String getOriginalName() { return originalName; }
    public long getSize() { return size; }
    public String getMimeType() { return mimeType; }

    public static class Builder {
        private String url;
        private String filename;
        private String originalName;
        private long size;
        private String mimeType;

        public Builder url(String v) { this.url = v; return this; }
        public Builder filename(String v) { this.filename = v; return this; }
        public Builder originalName(String v) { this.originalName = v; return this; }
        public Builder size(long v) { this.size = v; return this; }
        public Builder mimeType(String v) { this.mimeType = v; return this; }
        public UploadResponse build() { return new UploadResponse(this); }
    }
}