package com.aiplatform.gateway.dto;

public record UserSummaryResponse(
        String id,
        String email,
        String username,
        String role,
        String status,
        Boolean emailVerified
) {
}
