package com.chat.project.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;
    private final String uploadPath;

    public WebConfig(WebChatProperties properties,
                     @Value("${webchat.upload.path}") String uploadPath,
                     @Value("${FRONTEND_URL:}") String frontendUrl) {
        List<String> origins = new ArrayList<>(properties.getCors().getAllowedOrigins());
        if (frontendUrl != null && !frontendUrl.isBlank()) {
            origins.add(frontendUrl);
        }
        this.allowedOrigins = origins.stream()
                .filter(s -> s != null && !s.isBlank())
                .toList();
        this.uploadPath = uploadPath;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
