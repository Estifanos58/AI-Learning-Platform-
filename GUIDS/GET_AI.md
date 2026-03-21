# GET_AI.md

## Frontend Integration Guide

This guide explains how frontend clients should:

1. Fetch available AI models
2. Optionally manage user API keys
3. Send a message to an AI model
4. Consume streaming AI responses

All examples below use this backend base URL (as requested):

```
http://localhost:8000/api/internal
```

---

## 1) Authentication Headers

All endpoints require JWT auth.

Required headers:

```
Authorization: Bearer <access_token>
X-Correlation-ID: <optional-client-generated-id>
Content-Type: application/json
```

---

## 2) Fetch Available Models

Endpoint:

```
GET http://localhost:8000/api/internal/ai/models
```

Example response:

```json
[
   {
      "modelId": "b28f2a3f-6e13-4ad2-a9d0-9bb2f7b34343",
      "modelName": "gpt-4o",
      "provider": "openai",
      "contextLength": 128000,
      "supportsStreaming": true,
      "userKeyConfigured": true,
      "platformKeyAvailable": true,
      "description": "General purpose model"
   }
]
```

Frontend behavior recommendation:

- Show `modelName` and `provider` in model picker.
- Use `userKeyConfigured` to display “Your key connected” badge.
- If `platformKeyAvailable` is false and `userKeyConfigured` is false, disable the model and prompt key setup.

---

## 3) Manage User API Keys (Optional)

Create key:

```
POST http://localhost:8000/api/internal/ai/api-keys
```

Payload:

```json
{
   "modelId": "b28f2a3f-6e13-4ad2-a9d0-9bb2f7b34343",
   "apiKey": "sk-..."
}
```

Update key:

```
PUT http://localhost:8000/api/internal/ai/api-keys/{modelId}
```

Delete key:

```
DELETE http://localhost:8000/api/internal/ai/api-keys/{modelId}
```

Notes:

- Keys are encrypted server-side.
- Keys are never returned in responses.
- After create/update/delete, call `GET /ai/models` again to refresh `userKeyConfigured`.

---

## 4) Send Message to AI Model

Endpoint:

```
POST http://localhost:8000/api/internal/chat/messages
```

Minimum AI payload:

```json
{
   "aiModelId": "b28f2a3f-6e13-4ad2-a9d0-9bb2f7b34343",
   "content": "Explain Newton's second law with a simple example."
}
```

Optional fields:

- `chatroomId`: continue existing AI chatroom
- `fileId`: attach existing uploaded file context
- `fileBase64`, `fileOriginalName`, `fileContentType`: upload inline file with the message

Example response:

```json
{
   "message": {
      "id": "a0d23f09-0d89-4d8e-bdf2-6e93ebaf8b25",
      "chatroomId": "f10ff73f-b1b4-4c42-bec6-8d09468edaf9",
      "senderUserId": "c1af...",
      "aiModelId": "b28f...",
      "content": "Explain Newton's second law with a simple example.",
      "fileId": "",
      "createdAt": "2026-03-21T10:15:11"
   },
   "chatroomId": "f10ff73f-b1b4-4c42-bec6-8d09468edaf9",
   "isNewChatroom": true
}
```

Use `message.id` and `chatroomId` for streaming in the next step.

---

## 5) Stream AI Response (SSE)

Endpoint:

```
GET http://localhost:8000/api/internal/chat/messages/{messageId}/stream?chatroomId={chatroomId}
```

The server emits SSE events with `event: ai_chunk` and JSON string data.

Possible payload types inside `data`:

- `AI_CHUNK` → incremental text (`contentDelta`)
- `AI_COMPLETED` → final response (`finalContent`)
- `AI_FAILED` → error state (`error`)
- `AI_CANCELLED` → cancelled state

### React/TypeScript example with header support

Use `@microsoft/fetch-event-source` (recommended because JWT header is required):

```ts
import { fetchEventSource } from "@microsoft/fetch-event-source";

type StreamEnvelope = {
   type: "AI_CHUNK" | "AI_COMPLETED" | "AI_FAILED" | "AI_CANCELLED";
   data: {
      messageId: string;
      sequence?: number;
      contentDelta?: string;
      finalContent?: string;
      error?: string;
      done?: boolean;
   };
};

export async function streamAiResponse(params: {
   token: string;
   messageId: string;
   chatroomId: string;
   onChunk: (delta: string) => void;
   onDone: (finalText?: string) => void;
   onError: (error: string) => void;
}) {
   const { token, messageId, chatroomId, onChunk, onDone, onError } = params;

   let accumulated = "";

   await fetchEventSource(
      `http://localhost:8000/api/internal/chat/messages/${messageId}/stream?chatroomId=${chatroomId}`,
      {
         method: "GET",
         headers: {
            Authorization: `Bearer ${token}`,
            Accept: "text/event-stream",
         },
         onmessage(event) {
            if (event.event !== "ai_chunk") return;

            const envelope = JSON.parse(event.data) as StreamEnvelope;

            if (envelope.type === "AI_CHUNK") {
               const delta = envelope.data.contentDelta ?? "";
               accumulated += delta;
               onChunk(delta);
               return;
            }

            if (envelope.type === "AI_COMPLETED") {
               onDone(envelope.data.finalContent ?? accumulated);
               return;
            }

            if (envelope.type === "AI_FAILED") {
               onError(envelope.data.error ?? "AI generation failed");
               return;
            }

            if (envelope.type === "AI_CANCELLED") {
               onError("AI generation cancelled");
            }
         },
         onerror(err) {
            onError(String(err));
            throw err;
         },
      }
   );
}
```

Frontend state recommendation:

- Create placeholder AI message row immediately after send.
- Append each `contentDelta` to that message.
- On `AI_COMPLETED`, mark message as done.
- On `AI_FAILED` or `AI_CANCELLED`, mark message as errored/stopped.

---

## 6) Cancel Generation

Endpoint:

```
POST http://localhost:8000/api/internal/chat/messages/{messageId}/cancel
```

Use when user clicks Stop/Cancel while streaming.

---

## 7) End-to-End Frontend Sequence

1. `GET /ai/models` → show model options
2. User selects model and writes prompt
3. `POST /chat/messages` with `aiModelId` + `content`
4. Read `message.id` + `chatroomId` from response
5. Open SSE stream with `/chat/messages/{messageId}/stream?chatroomId=...`
6. Render chunks until `AI_COMPLETED`
7. Optional: `POST /chat/messages/{messageId}/cancel`

---

## 8) Versioning Note

AI Kafka events are now standardized on `v2` for request, chunk, completed, failed, and cancelled flows.
