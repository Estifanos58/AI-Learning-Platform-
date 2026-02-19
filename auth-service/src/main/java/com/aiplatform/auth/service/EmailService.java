package com.aiplatform.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final boolean mailEnabled;

    public EmailService(JavaMailSender mailSender,
                        @Value("${spring.mail.host:localhost}") String mailHost) {
        this.mailSender = mailSender;
        this.mailEnabled = !"localhost".equals(mailHost);
    }

    public void sendVerificationEmail(String toEmail, String token) {
        String subject = "Verify your email - AI Learning Platform";
        String body = "Please verify your email by using the following token:\n\n" + token +
                "\n\nThis token expires in 24 hours.";
        sendEmail(toEmail, subject, body);
    }

    public void sendPasswordResetEmail(String toEmail, String token) {
        String subject = "Reset your password - AI Learning Platform";
        String body = "You requested a password reset. Use the following token:\n\n" + token +
                "\n\nThis token expires in 1 hour. If you did not request this, ignore this email.";
        sendEmail(toEmail, subject, body);
    }

    private void sendEmail(String to, String subject, String body) {
        if (!mailEnabled) {
            log.info("[MOCK EMAIL] To: {} | Subject: {} | Body: {}", to, subject, body);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
