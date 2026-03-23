# FRONTEND AI AGENT IMPLEMENTATION INSTRUCTION

## Mission
Implement **complete AI interaction in the right sidebar only** on top of the existing chat frontend.

This instruction is strict:
- Keep existing 2-user chat behavior unchanged.
- Do not rewrite left-bar or main-bar behavior.
- Add/finish AI features only where AI communication exists (right bar).
- Use current backend contracts from `api-gateway`, `chat-service`, and `rag-service`.

---

## Non-Negotiable Scope Rules

1. **Do not break existing DM/two-user chat flow**
   - Existing send/list/socket behavior for normal user-to-user chat must continue exactly as-is.
   - If no AI model is selected, sending behaves exactly like current implementation.

2. **Right sidebar is the AI surface**
   - Model picker, AI status, stream progress, cancel action, and AI response rendering live in right bar.
   - Left bar and main bar should only consume already-existing chat state; no redesign.

3. **Backend endpoints and payloads must match current services**
   - Use gateway public routes (`/api/...`), not direct internal service calls.

---

## Backend Contracts To Implement Against

### 1) List AI models
- `GET /api/ai/models`
- Auth required (`Authorization: Bearer <token>`)
- Response item shape:
  - `modelId`, `modelName`, `provider`, `contextLength`, `supportsStreaming`,
  - `userKeyConfigured`, `platformKeyAvailable`, `description`

### 2) Optional API key management per model
- `POST /api/ai/api-keys`
- `PUT /api/ai/api-keys/{modelId}`
- `DELETE /api/ai/api-keys/{modelId}`
- After mutation, refresh model list.

### 3) Send chat/AI message (same endpoint already used)
- `POST /api/chat/messages`
- Request body supports:
  - `otherUserId`, `chatroomId`, `aiModelId`, `content`, `fileId`,
  - `fileBase64`, `fileOriginalName`, `fileContentType`
- For AI generation, include `aiModelId`.
- Response gives:
  - `message.id` (this is the stream/cancel message key)
  - `chatroomId`
  - `isNewChatroom`

### 4) Stream AI response (SSE)
- `GET /api/chat/messages/{messageId}/stream?chatroomId={chatroomId}`
- Must send auth header.
- Event name: `ai_chunk`
- Event data is JSON string envelope with `type`:
  - `AI_CHUNK`
  - `AI_COMPLETED`
  - `AI_FAILED`
  - `AI_CANCELLED`

### 5) Cancel AI generation
- `POST /api/chat/messages/{messageId}/cancel`
- Use while stream is active.

### 6) Existing real-time WebSocket stream (already present)
- `ws://<gateway>/ws/chat?token=<jwt>&chatroomId=<chatroomId>`
- Can emit AI events too (`AI_CHUNK`, `AI_COMPLETED`, `AI_FAILED`, `AI_CANCELLED`).
- Keep current websocket usage for normal chat events (`newMessage`, `typing`) unchanged.

---

## Required Right-Bar UX Behavior

1. **Model selection block**
   - Load models on right-bar mount (or app init with cache).
   - Show model name/provider and readiness state:
     - Ready when `platformKeyAvailable || userKeyConfigured`
     - Disabled otherwise.
   - Keep selected model per active chatroom (or global fallback).

2. **AI send behavior**
   - When user sends from AI-enabled right bar:
     - Call `POST /api/chat/messages` with `aiModelId`.
     - Keep normal message creation path unchanged.
   - If selected model is not ready, block AI send and show actionable warning.

3. **Streaming rendering**
   - Immediately create pending AI output item in UI.
   - Start SSE using returned `message.id` + `chatroomId`.
   - On `AI_CHUNK`: append `contentDelta` progressively.
   - On `AI_COMPLETED`: finalize message, stop loading state.
   - On `AI_FAILED`: show error state with retry affordance.
   - On `AI_CANCELLED`: show cancelled state and stop stream.

4. **Cancellation UX**
   - Show `Cancel` only while state is `streaming`.
   - On click, call cancel endpoint once, disable button until terminal state.
   - Accept either terminal event from SSE/WebSocket as completion of cancel flow.

5. **Conversation state badges in right bar**
   - Required states:
     - `idle`
     - `sending`
     - `streaming`
     - `completed`
     - `failed`
     - `cancelled`
   - State changes must be deterministic and tied to network/event outcomes.

---

## Data and State Requirements

Use a dedicated AI interaction state per active chatroom:

```ts
type AiConversationState = {
  selectedModelId: string | null;
  currentRequestMessageId: string | null;
  status: "idle" | "sending" | "streaming" | "completed" | "failed" | "cancelled";
  streamedText: string;
  error: string | null;
};
```

Rules:
- Never overwrite normal chat messages logic/state containers.
- AI stream state must be isolated so DM messaging remains stable.
- On chatroom change, keep previous room state cached; do not leak stream text between rooms.

---

## AI in Two-User Chatrooms (Important)

Support AI generation **inside existing two-user chatrooms** by adding `aiModelId` on message send when user explicitly chooses AI in right bar.

This does **not** change normal DM logic:
- DM without `aiModelId` = existing behavior only.
- DM with `aiModelId` = existing message send + AI pipeline/stream on top.

---

## Streaming Parser Contract

Parse `event.data` JSON into:

```ts
type AiStreamEnvelope = {
  type: "AI_CHUNK" | "AI_COMPLETED" | "AI_FAILED" | "AI_CANCELLED";
  data: {
    messageId?: string;
    sequence?: number;
    contentDelta?: string;
    finalContent?: string;
    error?: string;
    done?: boolean;
  };
};
```

Guardrails:
- Ignore events whose `messageId` does not match current active AI request in that room.
- De-duplicate terminal handling (`completed/failed/cancelled`) to avoid double-finalize from reconnects.

---

## Implementation Checklist (Must Pass)

1. Existing 2-user chat send/receive still works without selecting AI model.
2. Right bar shows model list from `/api/ai/models` and supports selection.
3. Sending with selected model includes `aiModelId` and starts AI stream.
4. Streamed text appears incrementally (not only final response).
5. `Cancel` stops in-progress generation and UI transitions to cancelled state.
6. Failed streams show recoverable error state.
7. Switching chatrooms does not corrupt AI state across rooms.
8. WebSocket normal chat events still function exactly as before.

---

## Minimal Pseudocode Flow

```ts
async function sendAiMessage(input: string, chatroomId?: string) {
  setAiState(room, { status: "sending", error: null, streamedText: "" });

  const res = await post("/api/chat/messages", {
    chatroomId,
    aiModelId: selectedModelId,
    content: input,
  });

  const messageId = res.message.id;
  const roomId = res.chatroomId;

  setAiState(roomId, {
    currentRequestMessageId: messageId,
    status: "streaming",
    streamedText: "",
  });

  await streamSse(`/api/chat/messages/${messageId}/stream?chatroomId=${roomId}`, {
    onChunk: (delta) => appendStream(roomId, delta),
    onCompleted: (finalText) => finalize(roomId, "completed", finalText),
    onFailed: (err) => finalize(roomId, "failed", null, err),
    onCancelled: () => finalize(roomId, "cancelled"),
  });
}

async function cancelAi(roomId: string) {
  const messageId = aiState[roomId].currentRequestMessageId;
  if (!messageId) return;
  await post(`/api/chat/messages/${messageId}/cancel`);
}
```

---

## Delivery Expectation

When implementing, produce a PR that includes:
- right-bar AI model selector integration,
- AI stream UI states,
- cancel action wiring,
- no regressions to existing DM/two-user chat logic.
