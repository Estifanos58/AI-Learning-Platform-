package com.aiplatform.gateway.controller;

import com.aiplatform.file.proto.DeleteFileRequest;
import com.aiplatform.file.proto.FileServiceGrpc;
import com.aiplatform.file.proto.GetFilePathRequest;
import com.aiplatform.file.proto.GetFileRequest;
import com.aiplatform.file.proto.ListMyFilesRequest;
import com.aiplatform.file.proto.ListSharedWithMeRequest;
import com.aiplatform.file.proto.ShareFileRequest;
import com.aiplatform.file.proto.UnshareFileRequest;
import com.aiplatform.file.proto.UpdateFileMetadataRequest;
import com.aiplatform.file.proto.UploadFileRequest;
import com.aiplatform.gateway.config.GrpcFileProperties;
import com.aiplatform.gateway.dto.ApiMessageResponse;
import com.aiplatform.gateway.dto.FileMetadataUpdateRequest;
import com.aiplatform.gateway.dto.FileResponse;
import com.aiplatform.gateway.dto.FileShareRequest;
import com.aiplatform.gateway.dto.FileUploadRequest;
import com.aiplatform.gateway.dto.ListFilesResponse;
import com.aiplatform.gateway.security.JwtValidationService;
import com.google.protobuf.ByteString;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/internal/files")
@RequiredArgsConstructor
public class GatewayFileController {

    private static final Metadata.Key<String> CORRELATION_ID_KEY = Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> SERVICE_SECRET_KEY = Metadata.Key.of("x-service-secret", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ID_KEY = Metadata.Key.of("x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ROLES_KEY = Metadata.Key.of("x-roles", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> UNIVERSITY_ID_KEY = Metadata.Key.of("x-university-id", Metadata.ASCII_STRING_MARSHALLER);

    @GrpcClient("file-service")
    private FileServiceGrpc.FileServiceBlockingStub fileStub;

    private final GrpcFileProperties grpcFileProperties;
    private final JwtValidationService jwtValidationService;

    @PostMapping
    public Mono<ResponseEntity<FileResponse>> uploadFile(
            @Valid @RequestBody FileUploadRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    byte[] content;
                    try {
                        content = Base64.getDecoder().decode(request.contentBase64());
                    } catch (IllegalArgumentException exception) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentBase64 must be valid Base64", exception);
                    }

                    UploadFileRequest grpcRequest = UploadFileRequest.newBuilder()
                            .setFileType(request.fileType())
                            .setOriginalName(request.originalName())
                            .setContentType(defaultString(request.contentType()))
                            .setContent(ByteString.copyFrom(content))
                            .setIsShareable(request.isShareable())
                            .build();

                    var response = withMetadata(principal).uploadFile(grpcRequest);
                    return ResponseEntity.ok(toDto(response));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @GetMapping("/{fileId}")
    public Mono<ResponseEntity<FileResponse>> getFileMetadata(
            @PathVariable String fileId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .getFileMetadata(GetFileRequest.newBuilder().setFileId(fileId).build());
                    return ResponseEntity.ok(toDto(response));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @DeleteMapping("/{fileId}")
    public Mono<ResponseEntity<ApiMessageResponse>> deleteFile(
            @PathVariable String fileId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .deleteFile(DeleteFileRequest.newBuilder().setFileId(fileId).build());
                    return ResponseEntity.ok(new ApiMessageResponse(response.getMessage()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @PostMapping("/{fileId}/share")
    public Mono<ResponseEntity<ApiMessageResponse>> shareFile(
            @PathVariable String fileId,
            @Valid @RequestBody FileShareRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .shareFile(ShareFileRequest.newBuilder()
                                    .setFileId(fileId)
                                    .setSharedWithUserId(request.sharedWithUserId())
                                    .build());
                    return ResponseEntity.ok(new ApiMessageResponse(response.getMessage()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @PostMapping("/{fileId}/unshare")
    public Mono<ResponseEntity<ApiMessageResponse>> unshareFile(
            @PathVariable String fileId,
            @Valid @RequestBody FileShareRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .unshareFile(UnshareFileRequest.newBuilder()
                                    .setFileId(fileId)
                                    .setSharedWithUserId(request.sharedWithUserId())
                                    .build());
                    return ResponseEntity.ok(new ApiMessageResponse(response.getMessage()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @PatchMapping("/{fileId}/metadata")
    public Mono<ResponseEntity<FileResponse>> updateMetadata(
            @PathVariable String fileId,
            @Valid @RequestBody FileMetadataUpdateRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .updateFileMetadata(UpdateFileMetadataRequest.newBuilder()
                                    .setFileId(fileId)
                                    .setIsShareable(request.isShareable())
                                    .build());
                    return ResponseEntity.ok(toDto(response));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @GetMapping("/my")
    public Mono<ResponseEntity<ListFilesResponse>> listMyFiles(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) com.aiplatform.file.proto.FileType fileType,
            @RequestParam(defaultValue = "false") boolean includeDeleted
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    ListMyFilesRequest.Builder requestBuilder = ListMyFilesRequest.newBuilder()
                            .setPage(Math.max(page, 0))
                            .setSize(Math.max(size, 1))
                            .setIncludeDeleted(includeDeleted);
                    if (fileType != null) {
                        requestBuilder.setFileType(fileType).setFilterByType(true);
                    }

                    var response = withMetadata(principal).listMyFiles(requestBuilder.build());
                    List<FileResponse> files = response.getFilesList().stream().map(this::toDto).toList();
                    return ResponseEntity.ok(new ListFilesResponse(files, response.getTotal()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @GetMapping("/shared-with-me")
    public Mono<ResponseEntity<ListFilesResponse>> listSharedWithMe(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .listSharedWithMe(ListSharedWithMeRequest.newBuilder()
                                    .setPage(Math.max(page, 0))
                                    .setSize(Math.max(size, 1))
                                    .build());
                    List<FileResponse> files = response.getFilesList().stream().map(this::toDto).toList();
                    return ResponseEntity.ok(new ListFilesResponse(files, response.getTotal()));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    @GetMapping("/{fileId}/path")
    public Mono<ResponseEntity<String>> getFilePath(
            @PathVariable String fileId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader
    ) {
        return Mono.fromCallable(() -> {
                    GatewayPrincipal principal = resolvePrincipal(authorization, correlationHeader);
                    var response = withMetadata(principal)
                            .getFilePath(GetFilePathRequest.newBuilder().setFileId(fileId).build());
                    return ResponseEntity.ok(response.getAbsolutePath());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(this::mapGrpcException);
    }

    private FileServiceGrpc.FileServiceBlockingStub withMetadata(GatewayPrincipal principal) {
        Metadata metadata = new Metadata();
        metadata.put(CORRELATION_ID_KEY, principal.correlationId());
        metadata.put(SERVICE_SECRET_KEY, grpcFileProperties.getServiceSecret());
        metadata.put(USER_ID_KEY, principal.userId());
        if (!principal.roles().isBlank()) {
            metadata.put(USER_ROLES_KEY, principal.roles());
        }
        if (!principal.universityId().isBlank()) {
            metadata.put(UNIVERSITY_ID_KEY, principal.universityId());
        }
        return fileStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private GatewayPrincipal resolvePrincipal(String authorization, String correlationHeader) {
        String token = bearerToken(authorization);
        Claims claims = jwtValidationService.parseClaims(token);

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token subject is missing");
        }

        String correlationId = correlationHeader;
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        String role = claims.get("role", String.class);
        String universityId = Optional.ofNullable(claims.get("universityId", String.class))
                .orElseGet(() -> defaultString(claims.get("university_id", String.class)));

        return new GatewayPrincipal(subject, defaultString(role), defaultString(universityId), correlationId);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        return authorization.substring(7);
    }

    private FileResponse toDto(com.aiplatform.file.proto.FileResponse response) {
        return new FileResponse(
                response.getId(),
                response.getOwnerId(),
                response.getFileType(),
                response.getOriginalName(),
                response.getStoredName(),
                response.getContentType(),
                response.getFileSize(),
                response.getStoragePath(),
                response.getIsShareable(),
                response.getDeleted(),
                response.getCreatedAt(),
                response.getUpdatedAt()
        );
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private Throwable mapGrpcException(Throwable throwable) {
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

    private record GatewayPrincipal(
            String userId,
            String roles,
            String universityId,
            String correlationId
    ) {
    }
}
