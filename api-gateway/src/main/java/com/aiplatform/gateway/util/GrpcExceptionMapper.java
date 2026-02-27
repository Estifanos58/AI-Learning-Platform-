package com.aiplatform.gateway.util;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class GrpcExceptionMapper {

    private GrpcExceptionMapper() {
    }

    public static Throwable toResponseStatus(Throwable throwable) {
        if (!(throwable instanceof StatusRuntimeException statusRuntimeException)) {
            return throwable;
        }

        Status.Code code = statusRuntimeException.getStatus().getCode();
        String description = statusRuntimeException.getStatus().getDescription();
        String message = description == null ? "Gateway request failed" : description;

        HttpStatus status = switch (code) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return new ResponseStatusException(status, message, throwable);
    }
}
