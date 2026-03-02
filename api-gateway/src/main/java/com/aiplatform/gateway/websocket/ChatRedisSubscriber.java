package com.aiplatform.gateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class ChatRedisSubscriber {

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    public ChatRedisSubscriber(ReactiveRedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    private ReactiveRedisMessageListenerContainer createContainer() {
        return new ReactiveRedisMessageListenerContainer(connectionFactory);
    }

    /**
     * Returns a Flux of JSON strings for the given chatroom Redis channel patterns.
     * Clients subscribe to newMessageSent.{chatroomId} and userTyping.{chatroomId}.
     */
    public Flux<String> subscribeToNewMessages(String chatroomId) {
        PatternTopic topic = PatternTopic.of("newMessageSent." + chatroomId);
        return Flux.defer(() -> createContainer().receive(topic))
                .map(message -> {
                    try {
                        Map<String, Object> envelope = Map.of(
                                "type", "newMessage",
                                "chatroomId", chatroomId,
                                "data", objectMapper.readValue(message.getMessage(), Object.class)
                        );
                        return objectMapper.writeValueAsString(envelope);
                    } catch (Exception e) {
                        log.warn("Failed to process newMessageSent event for chatroomId={}", chatroomId, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .onErrorResume(error -> {
                    log.warn("Redis subscription unavailable for newMessageSent chatroomId={}: {}", chatroomId, error.getMessage());
                    return Flux.empty();
                });
    }

    public Flux<String> subscribeToTyping(String chatroomId) {
        PatternTopic topic = PatternTopic.of("userTyping." + chatroomId);
        return Flux.defer(() -> createContainer().receive(topic))
                .map(message -> {
                    try {
                        Map<String, Object> envelope = Map.of(
                                "type", "typing",
                                "chatroomId", chatroomId,
                                "data", objectMapper.readValue(message.getMessage(), Object.class)
                        );
                        return objectMapper.writeValueAsString(envelope);
                    } catch (Exception e) {
                        log.warn("Failed to process userTyping event for chatroomId={}", chatroomId, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .onErrorResume(error -> {
                    log.warn("Redis subscription unavailable for userTyping chatroomId={}: {}", chatroomId, error.getMessage());
                    return Flux.empty();
                });
    }

    public Flux<String> subscribeToNewChatroom(String userId) {
        PatternTopic topic = PatternTopic.of("ChatroomCreatedWithMessage." + userId);
        return Flux.defer(() -> createContainer().receive(topic))
                .map(message -> {
                    try {
                        Map<String, Object> envelope = Map.of(
                                "type", "newChatroom",
                                "userId", userId,
                                "data", objectMapper.readValue(message.getMessage(), Object.class)
                        );
                        return objectMapper.writeValueAsString(envelope);
                    } catch (Exception e) {
                        log.warn("Failed to process ChatroomCreatedWithMessage event for userId={}", userId, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .onErrorResume(error -> {
                    log.warn("Redis subscription unavailable for ChatroomCreatedWithMessage userId={}: {}", userId, error.getMessage());
                    return Flux.empty();
                });
    }
}
