package com.aiplatform.file.grpc;

import com.aiplatform.file.domain.FileEntity;
import com.aiplatform.file.domain.FileType;
import com.aiplatform.file.exception.DuplicateShareException;
import com.aiplatform.file.exception.FileNotFoundException;
import com.aiplatform.file.exception.InvalidFileOperationException;
import com.aiplatform.file.exception.UnauthorizedFileAccessException;
import com.aiplatform.file.proto.DeleteFileRequest;
import com.aiplatform.file.proto.FilePathResponse;
import com.aiplatform.file.proto.FileResponse;
import com.aiplatform.file.proto.FileServiceGrpc;
import com.aiplatform.file.proto.GetFilePathRequest;
import com.aiplatform.file.proto.GetFileRequest;
import com.aiplatform.file.proto.ListFilesResponse;
import com.aiplatform.file.proto.ListMyFilesRequest;
import com.aiplatform.file.proto.ListSharedWithMeRequest;
import com.aiplatform.file.proto.ShareFileRequest;
import com.aiplatform.file.proto.SimpleResponse;
import com.aiplatform.file.proto.UnshareFileRequest;
import com.aiplatform.file.proto.UpdateFileMetadataRequest;
import com.aiplatform.file.proto.UploadFileRequest;
import com.aiplatform.file.service.AuthenticatedPrincipal;
import com.aiplatform.file.service.FileApplicationService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

@GrpcService
@RequiredArgsConstructor
public class FileGrpcService extends FileServiceGrpc.FileServiceImplBase {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final FileApplicationService fileApplicationService;

    @Override
    public void uploadFile(UploadFileRequest request, StreamObserver<FileResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            FileEntity saved = fileApplicationService.uploadFile(
                    principal,
                    FileType.valueOf(request.getFileType().name()),
                    request.getOriginalName(),
                    request.getContentType(),
                    request.getContent().toByteArray(),
                    request.getIsShareable()
            );
            responseObserver.onNext(toResponse(saved));
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void getFileMetadata(GetFileRequest request, StreamObserver<FileResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            FileEntity file = fileApplicationService.getMetadata(UUID.fromString(request.getFileId()), principal);
            responseObserver.onNext(toResponse(file));
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void deleteFile(DeleteFileRequest request, StreamObserver<SimpleResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            fileApplicationService.deleteFile(UUID.fromString(request.getFileId()), principal);
            responseObserver.onNext(SimpleResponse.newBuilder().setMessage("File deleted").build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void shareFile(ShareFileRequest request, StreamObserver<SimpleResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            fileApplicationService.shareFile(
                    UUID.fromString(request.getFileId()),
                    UUID.fromString(request.getSharedWithUserId()),
                    principal
            );
            responseObserver.onNext(SimpleResponse.newBuilder().setMessage("File shared").build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void unshareFile(UnshareFileRequest request, StreamObserver<SimpleResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            fileApplicationService.unshareFile(
                    UUID.fromString(request.getFileId()),
                    UUID.fromString(request.getSharedWithUserId()),
                    principal
            );
            responseObserver.onNext(SimpleResponse.newBuilder().setMessage("File unshared").build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void updateFileMetadata(UpdateFileMetadataRequest request, StreamObserver<FileResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            FileEntity updated = fileApplicationService.updateFileMetadata(
                    UUID.fromString(request.getFileId()),
                    request.getIsShareable(),
                    principal
            );
            responseObserver.onNext(toResponse(updated));
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void listMyFiles(ListMyFilesRequest request, StreamObserver<ListFilesResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            FileType filterType = null;
            if (request.getFilterByType()) {
                filterType = FileType.valueOf(request.getFileType().name());
            }
            var filesPage = fileApplicationService.listMyFiles(
                    principal,
                    request.getPage(),
                    request.getSize() == 0 ? 20 : request.getSize(),
                    filterType,
                    request.getIncludeDeleted()
            );

            ListFilesResponse.Builder builder = ListFilesResponse.newBuilder().setTotal(filesPage.getTotalElements());
            filesPage.getContent().forEach(file -> builder.addFiles(toResponse(file)));
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void listSharedWithMe(ListSharedWithMeRequest request, StreamObserver<ListFilesResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            var filesPage = fileApplicationService.listSharedWithMe(
                    principal,
                    request.getPage(),
                    request.getSize() == 0 ? 20 : request.getSize()
            );

            ListFilesResponse.Builder builder = ListFilesResponse.newBuilder().setTotal(filesPage.getTotalElements());
            filesPage.getContent().forEach(file -> builder.addFiles(toResponse(file)));
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    @Override
    public void getFilePath(GetFilePathRequest request, StreamObserver<FilePathResponse> responseObserver) {
        try {
            AuthenticatedPrincipal principal = requirePrincipal();
            UUID fileId = UUID.fromString(request.getFileId());
            String path = fileApplicationService.getFilePath(fileId, principal);
            responseObserver.onNext(FilePathResponse.newBuilder().setFileId(fileId.toString()).setAbsolutePath(path).build());
            responseObserver.onCompleted();
        } catch (Exception exception) {
            responseObserver.onError(toStatusException(exception));
        }
    }

    private AuthenticatedPrincipal requirePrincipal() {
        String rawUserId = GrpcContextKeys.USER_ID.get();
        if (rawUserId == null || rawUserId.isBlank()) {
            throw new UnauthorizedFileAccessException("Missing authenticated user metadata");
        }

        return new AuthenticatedPrincipal(
                UUID.fromString(rawUserId),
                GrpcContextKeys.UNIVERSITY_ID.get(),
                GrpcContextKeys.USER_ROLES.get(),
                GrpcContextKeys.CORRELATION_ID.get()
        );
    }

    private FileResponse toResponse(FileEntity file) {
        FileResponse.Builder builder = FileResponse.newBuilder()
                .setId(file.getId().toString())
                .setOwnerId(file.getOwnerId().toString())
                .setFileType(com.aiplatform.file.proto.FileType.valueOf(file.getFileType().name()))
                .setOriginalName(file.getOriginalName())
                .setStoredName(file.getStoredName())
                .setFileSize(file.getFileSize())
                .setStoragePath(file.getStoragePath())
                .setIsShareable(Boolean.TRUE.equals(file.getIsShareable()))
                .setDeleted(Boolean.TRUE.equals(file.getDeleted()))
                .setCreatedAt(TIME_FORMATTER.format(file.getCreatedAt()))
                .setUpdatedAt(TIME_FORMATTER.format(file.getUpdatedAt()));

        if (file.getContentType() != null) {
            builder.setContentType(file.getContentType());
        }

        return builder.build();
    }

    private io.grpc.StatusException toStatusException(Exception exception) {
        if (exception instanceof FileNotFoundException) {
            return Status.NOT_FOUND.withDescription(exception.getMessage()).asException();
        }
        if (exception instanceof UnauthorizedFileAccessException) {
            return Status.PERMISSION_DENIED.withDescription(exception.getMessage()).asException();
        }
        if (exception instanceof InvalidFileOperationException || exception instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).asException();
        }
        if (exception instanceof DuplicateShareException) {
            return Status.ALREADY_EXISTS.withDescription(exception.getMessage()).asException();
        }
        return Status.INTERNAL.withDescription("Unexpected internal error").withCause(exception).asException();
    }
}
