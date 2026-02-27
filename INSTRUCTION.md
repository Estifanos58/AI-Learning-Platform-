Objective

Create a production-ready File Service responsible for:

Storing and managing user files

Separating profile images from general user files

Managing file metadata

Supporting file sharing between users

Exposing file operations via gRPC

Publishing domain events when necessary

Storing files locally inside Docker-managed storage

Serving file paths (NOT raw file streaming to other services)

Allowing full internal DB access for RAG service (read-only recommended)

This service must integrate with:

auth-service (JWT validated at API Gateway)

user-profile-service (stores profile image reference)

RAG service (reads file paths + direct DB access for embedding)

api-gateway (gRPC only)

Kafka (for file events)

1. Project Metadata

Project Name: file-service
Build Tool: Maven
Language: Java 21
Framework: Spring Boot 3.x

Communication:

gRPC (Server)

Kafka (Producer optional for file events)

SQL Database (PostgreSQL for metadata)

Local File Storage (Docker volume)

2. Architectural Role

The File Service:

Owns file metadata

Owns physical file storage

Does NOT authenticate users

Trusts user identity from API Gateway metadata

Returns file path, not file content to other services

Enforces file ownership rules

Enforces sharing rules

3. Storage Strategy (Critical Design Decision)

We must separate:

A) Metadata Database

Use: PostgreSQL

Reason:

Strong relational modeling

Sharing rules require relational joins

Reliable indexing

Mature production support

B) File Binary Storage

Use:

Local filesystem inside Docker container

Mounted volume: /data/files

Reason:

Storing large binaries inside PostgreSQL (BYTEA) is inefficient

Filesystem storage is faster for large objects

Enables direct path access by RAG service

Cleaner separation of concerns

4. Storage Structure

Inside container:

/data
   /profile-images
   /user-files

Example:

/data/profile-images/{userId}/{filename}
/data/user-files/{userId}/{fileId}.{ext}

RAG service will receive:

fileId

absolutePath

It must not download from HTTP.
It reads directly from mounted shared volume.

5. Database Design
Table: files
CREATE TABLE files (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    file_type VARCHAR(20) NOT NULL, -- PROFILE_IMAGE, DOCUMENT
    original_name VARCHAR(255) NOT NULL,
    stored_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    file_size BIGINT NOT NULL,
    storage_path TEXT NOT NULL,
    is_shareable BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted BOOLEAN DEFAULT FALSE
);

Indexes:

CREATE INDEX idx_files_owner ON files(owner_id);
CREATE INDEX idx_files_type ON files(file_type);
Table: file_shares
CREATE TABLE file_shares (
    id UUID PRIMARY KEY,
    file_id UUID NOT NULL,
    shared_with_user_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE(file_id, shared_with_user_id)
);

Indexes:

CREATE INDEX idx_shared_user ON file_shares(shared_with_user_id);

Important:

No foreign key to user-profile DB

Only store UUID references

6. Domain Model
@Entity
@Table(name = "files")
public class FileEntity {

    @Id
    private UUID id;

    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    private FileType fileType; // PROFILE_IMAGE, DOCUMENT

    private String originalName;
    private String storedName;
    private String contentType;

    private Long fileSize;

    @Column(columnDefinition = "TEXT")
    private String storagePath;

    private Boolean isShareable;

    private Boolean deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

Enum:

public enum FileType {
    PROFILE_IMAGE,
    DOCUMENT
}
7. gRPC Definition (file.proto)

Service Name: FileService

Required RPCs
UploadFile(UploadFileRequest) returns (FileResponse)
GetFileMetadata(GetFileRequest) returns (FileResponse)
DeleteFile(DeleteFileRequest) returns (SimpleResponse)
ShareFile(ShareFileRequest) returns (SimpleResponse)
UnshareFile(UnshareFileRequest) returns (SimpleResponse)
UpdateFileMetadata(UpdateFileMetadataRequest) returns (FileResponse)
ListMyFiles(ListMyFilesRequest) returns (ListFilesResponse)
ListSharedWithMe(ListSharedWithMeRequest) returns (ListFilesResponse)
GetFilePath(GetFilePathRequest) returns (FilePathResponse)
8. Security Model

JWT validated at API Gateway.

Gateway forwards via metadata:

userId

roles

universityId

Rules:

Owner can delete file

Owner can modify metadata

Only owner can mark file shareable

Only owner can share/unshare

Shared users can access metadata + path

Non-shareable file cannot be shared

9. Business Rules
UploadFile

Extract userId from metadata

Validate max file size (configurable)

If fileType == PROFILE_IMAGE:

Ensure only 1 active profile image per user

Soft delete old image

Generate storedName = UUID + extension

Save file physically

Save metadata in DB

DeleteFile

Only owner allowed

Soft delete (deleted = true)

Optionally delete physical file

ShareFile

File must have isShareable = true

Owner only

Insert into file_shares

GetFilePath

Used by RAG service

Returns absolute path

Validate:

Owner OR

Shared user

10. Kafka Integration (Optional but Recommended)

Topics:

file.uploaded.v1

file.deleted.v1

Example event:

{
  "eventId": "uuid",
  "fileId": "uuid",
  "ownerId": "uuid",
  "fileType": "DOCUMENT",
  "path": "/data/user-files/xxx.pdf",
  "timestamp": "2026-02-27T10:00:00"
}

RAG Service can consume this to auto-embed.

11. Docker Configuration

Add to docker-compose.yml:

file-service:
  build: ./file-service
  ports:
    - "9092:9092"
  volumes:
    - file-storage:/data
  depends_on:
    - postgres
    - kafka

Volume:

volumes:
  file-storage:

Postgres DB:

file_service_db

Do NOT expose DB externally.

12. application.yml
server:
  port: 9092

spring:
  datasource:
    url: jdbc:postgresql://postgres:5432/file_service_db
    username: postgres
    password: postgres

  jpa:
    hibernate:
      ddl-auto: update

file:
  storage:
    root-path: /data
    max-size-mb: 50
13. Observability

Structured logging

Log fileId and ownerId

Include correlationId from metadata

Expose Actuator health endpoint

Log physical file write failures

14. Required Modification to User Profile Service

Currently:

avatar_url TEXT

This must be changed.

❌ REMOVE:
avatar_url TEXT
✅ REPLACE WITH:
profile_image_file_id UUID

Updated user_profiles table:

ALTER TABLE user_profiles
ADD COLUMN profile_image_file_id UUID;

Reason:

User Profile Service must NOT store file paths

It should only store fileId reference

File Service remains single source of truth

When uploading PROFILE_IMAGE:

File Service stores file

Returns fileId

API Gateway calls:
UpdateMyProfile(profileImageFileId)

User Profile Service only stores reference.

15. RAG Service Special Access

RAG service requirements:

Needs direct read access to file storage volume

Needs read-only access to file_service_db

Should not modify files

Update docker-compose:

rag-service:
  volumes:
    - file-storage:/data:ro

Grant read-only DB user.

16. Definition of Done

✔ Files stored physically in Docker volume
✔ Metadata stored in PostgreSQL
✔ Profile images separated from documents
✔ Sharing rules enforced
✔ Only owner can delete
✔ RAG can access file path directly
✔ gRPC communication works via API Gateway
✔ Service containerized
✔ File size validation enforced
✔ Soft delete works

17. Future Enhancements

Move to MinIO (S3 compatible)

Pre-signed URLs

File versioning

Virus scanning

File encryption at rest

CDN support

Rate limiting per user

Final Architecture Overview

Services:

auth-service

user-profile-service

file-service

rag-service

chat-service

File Service becomes:

Single Source of Truth for file storage.

Other services:
Never store file paths.
Only store fileId references.
Only request file path via gRPC.