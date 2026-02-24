## Objective
Create a production-ready API Gateway and refactor the existing Auth Service to transition from external REST endpoints to internal gRPC communication. The Gateway will act as the single entry point for clients (REST), while communicating with backend services via gRPC for high efficiency and type safety.

1. Project Metadata (New Service)
Project Name: api-gateway

Build Tool: Maven

Language: Java 21

Framework: Spring Boot 3.x / Spring Cloud Gateway

Communication: gRPC (Client), REST (Server)

2. Architecture Evolution
Phase A: The API Gateway (New)
Acts as a Reverse Proxy and Protocol Transcoder.

Receives REST/JSON requests from the public internet.

Converts them into gRPC/Protobuf calls for the Auth Service.

Handles CORS, Rate Limiting, and Global Security.

Phase B: The Auth Service (Refactor)
Remove/Deprecate REST controllers.

Implement gRPC Server stubs.

Maintain existing Domain, Service, and Repository layers.

3. Technology Stack & Dependencies
API Gateway:
spring-cloud-starter-gateway

spring-boot-starter-data-redis-reactive (for Rate Limiting)

grpc-client-spring-boot-starter

protobuf-java-util

spring-boot-starter-security

Auth Service (Additions):
grpc-server-spring-boot-starter

protobuf-java

4. Protobuf Definition (auth.proto)
Create a shared .proto file to define the contract between the Gateway and Auth Service.

Service Name: AuthService

RPCs Required:

Signup(SignupRequest) returns (AuthResponse)

Login(LoginRequest) returns (AuthResponse)

VerifyEmail(VerifyRequest) returns (SimpleResponse)

RefreshToken(RefreshRequest) returns (AuthResponse)

Logout(LogoutRequest) returns (SimpleResponse)

Security: Use Metadata (Headers) in gRPC to pass correlation IDs or existing JWTs if needed.

5. API Gateway Implementation
Advanced Rate Limiting
Implement Redis-based Key Rate Limiting.

Configure a KeyResolver to limit by IP address or Authenticated User ID.

Limits: 10 requests per second (Replenish Rate), 20 requests burst capacity.

CORS Configuration
Allow specific origins (configurable via application.yml).

Allowed Methods: GET, POST, PUT, DELETE, OPTIONS.

Allowed Headers: Content-Type, Authorization.

Allow Credentials: true.

gRPC Client Logic
Create a GatewayAuthController that accepts REST requests.

Use a ManagedChannel to communicate with the Auth Service.

Map REST DTOs to Protobuf Messages.

6. Auth Service Refactoring
Remove: controller package REST endpoints.

Add: grpc package.

Implement: AuthGrpcService extending the generated AuthServiceImplBase.

Logic: Inject existing Service layer beans into the gRPC implementation.

Error Handling: Map Java Exceptions to io.grpc.Status (e.g., UserNotFoundException -> Status.NOT_FOUND).

7. Security Requirements
Gateway Level: Implement a GlobalFilter to validate JWTs for protected routes (non-auth routes).

Auth Routes: /api/auth/** must remain public at the Gateway level to allow Signup/Login.

Secure Channel: Configure the gRPC communication to use TLS/SSL (ALTS if in a cloud environment) or at minimum, a shared secret in the gRPC metadata for service-to-service authentication.

8. Docker & Orchestration
Update docker-compose.yml:

Redis Service: Add a redis:7-alpine container for the Gateway rate limiter.

API Gateway Service:

Expose port 8080.

Depends on auth-service and redis.

Auth Service:

Expose port 9090 (gRPC port).

Do not expose REST ports to the public network.

9. application.yml (API Gateway)
YAML
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: grpc://auth-service:9090
          predicates:
            - Path=/api/auth/**
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins: "*"
            allowedMethods: "*"
  data:
    redis:
      host: redis
      port: 6379
10. Definition of Done
Project Structure: api-gateway exists alongside auth-service.

Communication: A user can POST /api/auth/login to the Gateway (REST), and the Gateway successfully calls the Auth Service via gRPC.

Rate Limiting: Sending 50 rapid requests to the Gateway triggers a 429 Too Many Requests.

CORS: Browser-based preflight requests (OPTIONS) are handled correctly.

Logging: Every gRPC call is logged in both services with a shared Correlation ID.

Protobuf: The .proto file is the "source of truth" for all Auth models.