package com.aiplatform.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.mail")
public class AppMailProperties {

    private String from = "no-reply@aiplatform.local";
    private boolean enabled = false;
    private String appName = "AI Learning Platform";
    private String verifyBaseUrl = "http://localhost:3000/verify-email?token=";
    private String resetPasswordBaseUrl = "http://localhost:3000/reset-password?token=";
}