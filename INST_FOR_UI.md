# API Gateway → RAG Service: UI Integration Instructions

This document summarizes recently added/changed API Gateway endpoints used by the UI to interact with the RAG Service for "direct" AI executions and direct chatrooms. For each endpoint it lists the HTTP method/path, request DTO, mapped gRPC receiver, response DTO, common error cases and recommended UI handling. It also documents Server-Sent Events (SSE) behaviors and Redis stream payload shapes used for live updates.

**Why this file exists**: UI developers should use these guidelines for wiring start/execution status, SSE bootstrapping + live tailing, chatroom CRUD and message listing, sanitization flags, and error handling UX.

---

**How to read this file**:
- **Endpoint**: HTTP method + path
- **Request**: fields the UI should send
- **gRPC RPC**: RAG service RPC invoked by the Gateway
- **Response**: Gateway JSON response shape
- **Errors**: HTTP error codes that may be returned and UI guidance

---

**1) Start direct AI execution**
- **Endpoint**: POST /api/internal/ai/executions
- **Request**: `StartAiExecutionRequest` JSON
  - `requestId` (optional) — client-generated id to de-duplicate/reconnect
  - `prompt` (string, required)
  - `mode` (string, optional) — e.g. `chat` or `deep` (default deep)
  - `aiModelId` (optional)
  - `fileIds` (optional list)
  - `chatroomId` (optional) — attach to existing chatroom
  - `messageId` (optional) — attach to an existing message when continuing
  - `options` (optional map)
- **gRPC RPC**: `RagService.ExecuteDirect(ExecuteDirectRequest)`
- **Gateway Response**: `StartAiExecutionResponse`
  - `status` (string)
  - `requestId` (string)
  - `streamKey` (string) — internal stream key
  - `accepted` (boolean)
  - `chatroomId` (string|null)
  - `messageId` (string|null)
- **SSE/stream**: After accepted, UI should call SSE endpoint below to receive replay + live updates.
- **Errors & UI guidance**:
  - 400 BAD_REQUEST (validation): show field errors from `ApiErrorResponse.details` and mark invalid fields.
  - 401 UNAUTHORIZED: redirect to login / refresh token flow.
  - 403 FORBIDDEN: show permission denied UI and disable the action.
  - 409 CONFLICT: show conflict; suggest user to retry or load latest execution state.
  - 429 TOO_MANY_REQUESTS: inform user and display retry-after or rate-limit message.
  - 502/503/504 (upstream errors/timeouts): show "Service temporarily unavailable — try again" and allow retry.
  - Prompt sanitization: request may pass through `PromptInjectionSanitizationFilter`. When present, Gateway attaches header `X-Sanitization-Flags` (comma-separated flags). The UI may display a small audit badge or a tooltip indicating sanitization occurred (e.g. `prompt_injection_detected`, `null_bytes_removed`).

---

**2) Get execution status**
- **Endpoint**: GET /api/internal/ai/executions/{executionId}
- **Request**: path param `executionId`
- **gRPC RPC**: `RagService.GetExecution(GetExecutionRequest)`
- **Gateway Response**: `ExecutionStatusResponse`
  - `executionId`, `status` (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED), `messageId`, `chatroomId`, `createdAt`, `completedAt`, `error` (string|null), `streamKey`
- **Errors & UI guidance**:
  - 404 NOT_FOUND: show "Execution not found" and allow user to re-check requestId or re-run.
  - 401/403: same handling as above.
  - 502/503/504: show upstream/unavailable message and retry option.

---

**3) Cancel execution**
- **Endpoint**: DELETE /api/internal/ai/executions/{executionId}
- **Request**: path param `executionId`
- **gRPC RPC**: `RagService.CancelExecution(CancelExecutionRequest)`
- **Gateway Response**: `ApiMessageResponse` — `{ "status": "..." }`
- **Errors & UI guidance**:
  - 404 NOT_FOUND: show "Execution not found" (may already be completed).
  - 409 CONFLICT: if cancellation cannot be applied (race), show message and refresh status.
  - 401/403: auth/permission handling.

---

**4) SSE stream bootstrap + live-tail**
- **Endpoint**: GET /api/internal/ai/executions/{executionId}/stream (Server-Sent Events `text/event-stream`)
- **Behavior**:
  - Gateway calls `RagService.GetExecutionStreamBootstrap(GetExecutionStreamBootstrapRequest)`.
  - If bootstrap status is `COMPLETED`/`FAILED`/`CANCELLED`, the Gateway returns a single SSE event with the final result (no live-tail).
  - If bootstrap status is `RUNNING`, the Gateway will:
    1. If `partialContent` exists, emit one SSE event `event: ai_replay` with payload `{ status: "running", content: <partialContent> }` to replay persisted assistant partials.
    2. Then subscribe to the Redis stream for this execution (stream key `stream:ai:{executionId}`) using a live-tail subscription and forward records as SSE events.
- **SSE event names emitted by Gateway** (client should listen by event name):
  - `ai_replay`: emitted once on bootstrap when persisted partial exists.
    - data JSON: `{ "status": "running", "content": "<partial string>" }`
  - `ai_chunk`: forwarded live stream chunk (data is a JSON envelope produced by the Gateway's Redis subscriber). The payload is a JSON string — parse it as JSON.
    - normalized envelope shape (stringified JSON): `{ "type": "AI_CHUNK" | "AI_COMPLETED" | "AI_FAILED" | "AI_CANCELLED" | "AI_EVENT", "executionId": "...", "recordId": "<redis-record-id>", "data": { ... } }`
    - For `AI_CHUNK`, `data` typically contains `{ "messageId": "...", "content": "<chunk>", ... }`. Append `content` to the running assistant message.
    - For `AI_COMPLETED`, `data` typically includes the final content or metadata; treat as terminal and finalize UI.
    - For `AI_FAILED` and `AI_CANCELLED`, treat as terminal with error/cancel state.
  - `ai_completed` / `ai_failed` / `ai_cancelled`: produced by bootstrap when final state is known, with small payloads:
    - `ai_completed` data example: `{ "status": "completed", "content": "<final content>" }`
    - `ai_failed` data example: `{ "status": "failed", "error": "<error message>" }`
    - `ai_cancelled` data example: `{ "status": "cancelled" }`
- **Live-tail considerations**:
  - If Redis subscription is unavailable, Gateway returns an empty stream for live-tail (it logs server-side). UI should detect no further SSE events and fallback to polling `GET /executions/{id}` for final status or show an advisory "Live updates currently unavailable — refresh to check status".
  - SSE reconnection: UI should reconnect with exponential backoff. On reconnect, re-call SSE endpoint to receive bootstrap + live-tail; bootstrap will include persisted partials so the UI can resume where it left off (use `requestId` or `executionId` to correlate).

---

**5) Direct chatroom endpoints**
- **List chatrooms**
  - **Endpoint**: GET /api/chatrooms
  - **gRPC RPC**: `RagService.ListChatrooms(ListChatroomsRequest)`
  - **Response**: `ListDirectChatroomsResponse` with `chatrooms` array and `total` count. Each chatroom: `{ id, title, createdAt, updatedAt }`.
  - **Errors**: 401/403/502/503 etc — apply same UX rules.

- **Get chatroom (with recent messages)**
  - **Endpoint**: GET /api/chatrooms/{chatroomId}
  - **gRPC RPC**: `RagService.GetChatroom(GetChatroomRequest)`
  - **Response**: `DirectChatroomDetailResponse` with chatroom fields + messages list. Each message contains `{ id, chatroomId, role, content, status, createdAt, updatedAt }`.

- **List messages**
  - **Endpoint**: GET /api/chatrooms/{chatroomId}/messages?page=&size=
  - **gRPC RPC**: `RagService.ListChatroomMessages(ListChatroomMessagesRequest)`
  - **Response**: `ListDirectChatMessagesResponse` with `messages` and `total`.

- **Update chatroom title**
  - **Endpoint**: PATCH /api/chatrooms/{chatroomId}
  - **Request**: `UpdateChatroomTitleRequest` with `title`
  - **gRPC RPC**: `RagService.UpdateChatroomTitle(UpdateChatroomTitleRequest)`
  - **Response**: `DirectChatroomResponse` (single chatroom)

- **Delete chatroom**
  - **Endpoint**: DELETE /api/chatrooms/{chatroomId}
  - **gRPC RPC**: `RagService.DeleteChatroom(DeleteChatroomRequest)`
  - **Response**: `ApiMessageResponse` with `status` string

- **Errors & UI guidance for chatrooms**: same mapping as above (400/401/403/404/409/5xx). For 404, display not-found and offer navigation back to chat list. For delete confirm UI, handle 409 as conflict and refresh list.

---

**6) Centralized error envelope from Gateway**
- On errors the Gateway returns a JSON `ApiErrorResponse` with fields:
  - `timestamp`, `status` (HTTP code), `error` (reason phrase), `code` (machine code), `message` (safe message), `path`, `correlationId`, `details` (map for validation fields)
- UI usage:
  - Show `message` to users in alerts.
  - For validation errors use `details` to mark fields and show inline validation messages.
  - Include `correlationId` when reporting issues to support.

---

**7) gRPC → HTTP status mapping (summary)**
- The Gateway maps gRPC Status.Code to HTTP as follows (via GrpcExceptionMapper):
  - INVALID_ARGUMENT -> 400 BAD_REQUEST
  - UNAUTHENTICATED -> 401 UNAUTHORIZED
  - PERMISSION_DENIED -> 403 FORBIDDEN
  - NOT_FOUND -> 404 NOT_FOUND
  - ALREADY_EXISTS -> 409 CONFLICT
  - RESOURCE_EXHAUSTED -> 429 TOO_MANY_REQUESTS
  - DEADLINE_EXCEEDED -> 504 GATEWAY_TIMEOUT
  - UNAVAILABLE -> 503 SERVICE_UNAVAILABLE
  - ABORTED -> 409 CONFLICT
  - FAILED_PRECONDITION -> 400 BAD_REQUEST
  - Other -> 502 BAD_GATEWAY

UI guidance: map the codes to behaviors (login, show permission denial, not-found UX, retry/backoff for 503/504, explain rate limiting with retry-after for 429).

---

**8) UX recommendations (concise)**
- Start-execution flow (recommended):
  1. POST start endpoint → on 202 Accepted: read `requestId`/`executionId` and immediately open SSE `/executions/{executionId}/stream`.
  2. On SSE `ai_replay` event: render any persisted partial content as initial assistant output.
  3. On SSE `ai_chunk` (AI_CHUNK): append chunk content to in-progress assistant message.
  4. On SSE terminal (`ai_completed` / `ai_failed` / `ai_cancelled` or AI_COMPLETED/AI_FAILED/AI_CANCELLED in chunk envelope): finalize UI state and stop reconnection attempts.
  5. If SSE is interrupted unexpectedly, attempt reconnect with exponential backoff and re-bootstrap. Use `requestId`/`executionId` to reconcile duplicates.
- Cancellation: call DELETE endpoint and optimistically mark message as cancelled in UI; confirm cancellation via GET status.
- When live updates are unavailable: fall back to polling `GET /api/internal/ai/executions/{id}` every few seconds until terminal state.
- Display `X-Sanitization-Flags` when present as a small badge on the user-submitted prompt that links to an explanation.

---

**9) Sample SSE client pseudo-workflow (high-level)**
- Open an EventSource to `/api/internal/ai/executions/{executionId}/stream` including Authorization header.
- Listen for events:
  - `ai_replay` → JSON.parse(data) → set assistant content to `content` and mark as running.
  - `ai_chunk` → JSON.parse(data) → append `data.content` to assistant message.
  - `ai_completed` → JSON.parse(data) → finalize assistant message using `content`.
  - `ai_failed` → JSON.parse(data) → show error from `error` and mark failed.
  - `ai_cancelled` → mark cancelled.
- On `error` from EventSource: attempt reconnect with backoff, re-open stream and re-apply bootstrap.

---

**10) Useful implementation notes for UI engineers**
- Always send Authorization header (bearer token). The Gateway validates and will return 401 on missing/invalid tokens.
- Preserve and forward `requestId` from the start response to help with dedup/reconnect logic.
- Use `GET /api/internal/ai/executions/{executionId}` as a fallback polling endpoint when SSE is unreliable.
- Use `correlationId` returned in error responses for support traces.
- Treat server-side sanitized prompts as informational — server preserves intent but flags suspicious content.

---

If you want, I can also generate small example snippets for React using `EventSource` and fetch flows (start + SSE + fallback polling), or create a TypeScript SDK wrapper that handles bootstrap + live-tail reconnection. Which would you prefer next?
