package com.aiplatform.auth.service;

import com.aiplatform.auth.domain.User;
import com.aiplatform.auth.config.AppMailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final AppMailProperties appMailProperties;

    @Override
    public void sendEmail(String to, String subject, String body) {
        sendOrLog(to, subject, body);
    }

    @Override
    public void sendVerificationEmail(User user, String token) {
        String verificationLink = appMailProperties.getVerifyBaseUrl() + token;
        String body = "Hello,\n\n"
                + "Welcome to " + appMailProperties.getAppName() + ".\n"
                + "Please verify your email using the link below:\n"
                + verificationLink + "\n\n"
                + "If you did not create this account, you can ignore this message.\n\n"
                + "Regards,\n"
                + appMailProperties.getAppName();

        sendEmail(user.getEmail(), "Verify your account", body);
    }

    @Override
    public void sendPasswordResetEmail(User user, String token) {
        String resetLink = appMailProperties.getResetPasswordBaseUrl() + token;
        String body = "Hello,\n\n"
                + "We received a request to reset your password for " + appMailProperties.getAppName() + ".\n"
                + "Use the link below to set a new password:\n"
                + resetLink + "\n\n"
                + "If you did not request a password reset, you can ignore this message.\n\n"
                + "Regards,\n"
                + appMailProperties.getAppName();

        sendEmail(user.getEmail(), "Reset your password", body);
    }

    private void sendOrLog(String to, String subject, String body) {
        if (!appMailProperties.isEnabled()) {
            log.info("Mail disabled. Mock email to={}, subject={}, body={}", to, subject, body);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setFrom(appMailProperties.getFrom());
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception exception) {
            log.warn("Failed to send email. Falling back to mock log. to={}, subject={}, body={}", to, subject, body, exception);
        }
    }
}