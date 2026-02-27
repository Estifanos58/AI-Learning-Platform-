package com.aiplatform.file.service;

import com.aiplatform.file.config.KafkaFileTopicProperties;
import com.aiplatform.file.domain.FileEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileEventPublisher {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final KafkaFileTopicProperties topicProperties;
    @Nullable
    private final KafkaTemplate<String, String> kafkaTemplate;

    public void publishUploaded(FileEntity file) {
        if (!topicProperties.enabled() || kafkaTemplate == null) {
            return;
        }
        kafkaTemplate.send(topicProperties.uploadedTopic(), file.getId().toString(), toPayload("file.uploaded.v1", file));
    }

    public void publishDeleted(FileEntity file) {
        if (!topicProperties.enabled() || kafkaTemplate == null) {
            return;
        }
        kafkaTemplate.send(topicProperties.deletedTopic(), file.getId().toString(), toPayload("file.deleted.v1", file));
    }

    private String toPayload(String eventType, FileEntity file) {
        String timestamp = TIME_FORMATTER.format(LocalDateTime.now());
        String path = file.getStoragePath() == null ? "" : file.getStoragePath().replace("\"", "");
        return "{"
                + "\"eventId\":\"" + UUID.randomUUID() + "\"," 
                + "\"eventType\":\"" + eventType + "\"," 
                + "\"fileId\":\"" + file.getId() + "\"," 
                + "\"ownerId\":\"" + file.getOwnerId() + "\"," 
                + "\"fileType\":\"" + file.getFileType().name() + "\"," 
                + "\"path\":\"" + path + "\"," 
                + "\"timestamp\":\"" + timestamp + "\""
                + "}";
    }
}
