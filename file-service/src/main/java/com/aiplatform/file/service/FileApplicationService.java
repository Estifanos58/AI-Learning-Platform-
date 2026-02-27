package com.aiplatform.file.service;

import com.aiplatform.file.config.FileStorageProperties;
import com.aiplatform.file.domain.FileEntity;
import com.aiplatform.file.domain.FileShareEntity;
import com.aiplatform.file.domain.FileType;
import com.aiplatform.file.exception.DuplicateShareException;
import com.aiplatform.file.exception.FileNotFoundException;
import com.aiplatform.file.exception.InvalidFileOperationException;
import com.aiplatform.file.exception.UnauthorizedFileAccessException;
import com.aiplatform.file.repository.FileRepository;
import com.aiplatform.file.repository.FileShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileApplicationService {

    private final FileRepository fileRepository;
    private final FileShareRepository fileShareRepository;
    private final FileStorageProperties storageProperties;
    private final FileEventPublisher fileEventPublisher;

    @Transactional
    public FileEntity uploadFile(AuthenticatedPrincipal principal,
                                 FileType fileType,
                                 String originalName,
                                 String contentType,
                                 byte[] content,
                                 boolean isShareable) {
        requireAuthenticated(principal);

        if (content == null || content.length == 0) {
            throw new InvalidFileOperationException("Uploaded file content is empty");
        }

        long maxSizeBytes = storageProperties.maxSizeMb() * 1024L * 1024L;
        if (content.length > maxSizeBytes) {
            throw new InvalidFileOperationException("File size exceeds configured limit");
        }

        UUID fileId = UUID.randomUUID();
        String extension = extension(originalName);
        String storedName = storedNameFor(fileType, fileId, extension);
        Path targetPath = targetPath(fileType, principal.userId(), storedName);

        writeFile(targetPath, content, principal);

        if (fileType == FileType.PROFILE_IMAGE) {
            fileRepository.findActiveProfileImageByOwnerId(principal.userId(), FileType.PROFILE_IMAGE)
                    .ifPresent(existing -> existing.setDeleted(Boolean.TRUE));
            isShareable = false;
        }

        FileEntity entity = FileEntity.builder()
                .id(fileId)
                .ownerId(principal.userId())
                .fileType(fileType)
                .originalName(safeOriginalName(originalName, storedName))
                .storedName(storedName)
                .contentType(blankToNull(contentType))
                .fileSize((long) content.length)
                .storagePath(targetPath.toAbsolutePath().toString())
                .isShareable(isShareable)
                .deleted(Boolean.FALSE)
                .build();

        FileEntity saved = fileRepository.save(entity);
        fileEventPublisher.publishUploaded(saved);

        log.info("File uploaded. fileId={}, ownerId={}, correlationId={}", saved.getId(), saved.getOwnerId(), principal.correlationId());
        return saved;
    }

    @Transactional(readOnly = true)
    public FileEntity getMetadata(UUID fileId, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateCanAccess(file, principal);
        return file;
    }

    @Transactional
    public void deleteFile(UUID fileId, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateOwner(file, principal);

        file.setDeleted(Boolean.TRUE);
        fileRepository.save(file);
        fileShareRepository.deleteAllByFileId(fileId);
        fileEventPublisher.publishDeleted(file);

        log.info("File soft deleted. fileId={}, ownerId={}, correlationId={}", file.getId(), file.getOwnerId(), principal.correlationId());
    }

    @Transactional
    public void shareFile(UUID fileId, UUID sharedWithUserId, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateOwner(file, principal);

        if (!Boolean.TRUE.equals(file.getIsShareable())) {
            throw new InvalidFileOperationException("File is not shareable");
        }

        if (fileShareRepository.existsByFileIdAndSharedWithUserId(fileId, sharedWithUserId)) {
            throw new DuplicateShareException("File already shared with this user");
        }

        FileShareEntity shareEntity = FileShareEntity.builder()
                .id(UUID.randomUUID())
                .fileId(fileId)
                .sharedWithUserId(sharedWithUserId)
                .createdAt(LocalDateTime.now())
                .build();
        fileShareRepository.save(shareEntity);

        log.info("File shared. fileId={}, ownerId={}, targetUserId={}, correlationId={}",
                file.getId(), file.getOwnerId(), sharedWithUserId, principal.correlationId());
    }

    @Transactional
    public void unshareFile(UUID fileId, UUID sharedWithUserId, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateOwner(file, principal);

        fileShareRepository.deleteByFileIdAndSharedWithUserId(fileId, sharedWithUserId);
        log.info("File unshared. fileId={}, ownerId={}, targetUserId={}, correlationId={}",
                file.getId(), file.getOwnerId(), sharedWithUserId, principal.correlationId());
    }

    @Transactional
    public FileEntity updateFileMetadata(UUID fileId, boolean isShareable, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateOwner(file, principal);

        if (file.getFileType() == FileType.PROFILE_IMAGE && isShareable) {
            throw new InvalidFileOperationException("Profile image cannot be marked as shareable");
        }

        file.setIsShareable(isShareable);
        if (!isShareable) {
            fileShareRepository.deleteAllByFileId(fileId);
        }

        FileEntity saved = fileRepository.save(file);
        log.info("File metadata updated. fileId={}, ownerId={}, shareable={}, correlationId={}",
                saved.getId(), saved.getOwnerId(), saved.getIsShareable(), principal.correlationId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<FileEntity> listMyFiles(AuthenticatedPrincipal principal, int page, int size, FileType fileType, boolean includeDeleted) {
        requireAuthenticated(principal);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100));
        return fileRepository.listOwnedFiles(principal.userId(), fileType, includeDeleted, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FileEntity> listSharedWithMe(AuthenticatedPrincipal principal, int page, int size) {
        requireAuthenticated(principal);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100));
        return fileRepository.listSharedWith(principal.userId(), pageable);
    }

    @Transactional(readOnly = true)
    public String getFilePath(UUID fileId, AuthenticatedPrincipal principal) {
        FileEntity file = findActiveFile(fileId);
        validateCanAccess(file, principal);
        return file.getStoragePath();
    }

    private void writeFile(Path targetPath, byte[] content, AuthenticatedPrincipal principal) {
        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            log.error("Physical file write failed. path={}, ownerId={}, correlationId={}",
                    targetPath, principal.userId(), principal.correlationId(), exception);
            throw new InvalidFileOperationException("Failed to persist physical file");
        }
    }

    private FileEntity findActiveFile(UUID fileId) {
        return fileRepository.findByIdAndDeletedFalse(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found"));
    }

    private void validateOwner(FileEntity file, AuthenticatedPrincipal principal) {
        requireAuthenticated(principal);
        if (!file.getOwnerId().equals(principal.userId())) {
            throw new UnauthorizedFileAccessException("Only owner can perform this operation");
        }
    }

    private void validateCanAccess(FileEntity file, AuthenticatedPrincipal principal) {
        requireAuthenticated(principal);
        if (file.getOwnerId().equals(principal.userId())) {
            return;
        }
        boolean shared = fileShareRepository.existsByFileIdAndSharedWithUserId(file.getId(), principal.userId());
        if (!shared) {
            throw new UnauthorizedFileAccessException("No access to this file");
        }
    }

    private void requireAuthenticated(AuthenticatedPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedFileAccessException("Missing authenticated user metadata");
        }
    }

    private String safeOriginalName(String originalName, String fallback) {
        String cleaned = blankToNull(originalName);
        return cleaned == null ? fallback : cleaned;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String extension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(lastDot + 1).replaceAll("[^A-Za-z0-9]", "");
        return ext.isBlank() ? "" : ext.toLowerCase();
    }

    private String storedNameFor(FileType fileType, UUID fileId, String extension) {
        if (fileType == FileType.PROFILE_IMAGE) {
            return extension.isBlank() ? UUID.randomUUID().toString() : UUID.randomUUID() + "." + extension;
        }
        return extension.isBlank() ? fileId.toString() : fileId + "." + extension;
    }

    private Path targetPath(FileType fileType, UUID userId, String storedName) {
        Path root = Paths.get(storageProperties.rootPath());
        if (fileType == FileType.PROFILE_IMAGE) {
            return root.resolve("profile-images").resolve(userId.toString()).resolve(storedName);
        }
        return root.resolve("user-files").resolve(userId.toString()).resolve(storedName);
    }
}
