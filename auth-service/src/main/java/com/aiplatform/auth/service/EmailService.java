package com.aiplatform.auth.service;

import com.aiplatform.auth.domain.User;

public interface EmailService {

    void sendEmail(String to, String subject, String body);

    void sendVerificationEmail(User user, String token);

    void sendPasswordResetEmail(User user, String token);
}