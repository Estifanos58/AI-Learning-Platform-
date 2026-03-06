package com.aiplatform.auth.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationEmailEventPublisher {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.user-email-verification:user.email.verification.v1}")
    private String verificationTopic;

    public void publish(UUID userId, String email, String username, String verificationCode, String correlationId) {
        VerificationEmailEvent event = new VerificationEmailEvent(
                UUID.randomUUID().toString(),
                userId.toString(),
                email,
                username,
                verificationCode,
                FORMATTER.format(LocalDateTime.now())
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record = new ProducerRecord<>(verificationTopic, userId.toString(), payload);
            record.headers().add(new RecordHeader("correlationId", safeHeaderValue(correlationId)));
            record.headers().add(new RecordHeader("eventVersion", "v1".getBytes(StandardCharsets.UTF_8)));

            kafkaTemplate.send(record);
            log.info("Published verification email event. userId={}, eventId={}", userId, event.eventId());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize verification email event", exception);
        }
    }

    private byte[] safeHeaderValue(String value) {
        String resolved = value == null || value.isBlank() ? "" : value;
        return resolved.getBytes(StandardCharsets.UTF_8);
    }
}
