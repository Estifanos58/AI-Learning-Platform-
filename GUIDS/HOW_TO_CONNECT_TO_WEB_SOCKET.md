# HOW TO CONNECT TO WEB SOCKET (Frontend)

This guide explains exactly how the frontend should receive:
- new chatroom created events (scoped by logged-in user)
- new message events (scoped by chatroom id)

## 1) WebSocket endpoint

Use this endpoint from the API Gateway:

`ws://<API_GATEWAY_HOST>/ws/chat?token=<JWT>`

Optional query parameter:
- `chatroomId=<chatroom-uuid>` to also subscribe to message/typing/AI events for that chatroom.

> You do **not** need to pass `userId` in query params. The backend derives it from the JWT subject.

---

## 2) Subscription flow (recommended)

### Step A — Open a user-level socket first
Open one socket with token only (no chatroomId):

`ws://<API_GATEWAY_HOST>/ws/chat?token=<JWT>`

This socket receives `newChatroom` events for that user.

### Step B — For each chatroom, open chatroom-level socket
For each chatroom from your chatroom list API, open:

`ws://<API_GATEWAY_HOST>/ws/chat?token=<JWT>&chatroomId=<chatroom-uuid>`

This socket receives:
- `newMessage`
- `typing`
- `AI_CHUNK`, `AI_COMPLETED`, `AI_FAILED`, `AI_CANCELLED`
- and also `newChatroom`

### Step C — When a `newChatroom` event arrives
1. Read `data.chatroomId`.
2. Add this chatroom to local chatroom state immediately.
3. Open a chatroom-level socket for the new `chatroomId`.
4. Start receiving `newMessage` events on that room socket.

---

## 3) Event envelope format

Gateway wraps every Redis payload into a JSON envelope:

```json
{
  "type": "newChatroom | newMessage | typing | AI_CHUNK | AI_COMPLETED | AI_FAILED | AI_CANCELLED",
  "chatroomId": "<chatroom-id>",
  "data": { "...raw payload..." }
}
```

### Example: `newChatroom`

```json
{
  "type": "newChatroom",
  "userId": "<jwt-subject-user-id>",
  "data": {
    "chatroomId": "81cc1c6f-ce44-415b-9e83-7e76a8b87eaa",
    "otherUserId": "<other-participant-user-id>",
    "userId": "<sender-user-id>",
    "message": {
      "id": "...",
      "chatroomId": "...",
      "senderUserId": "...",
      "content": "...",
      "createdAt": "2026-03-17T13:16:15.851"
    }
  }
}
```

### Example: `newMessage`

```json
{
  "type": "newMessage",
  "chatroomId": "81cc1c6f-ce44-415b-9e83-7e76a8b87eaa",
  "data": {
    "chatroomId": "81cc1c6f-ce44-415b-9e83-7e76a8b87eaa",
    "userId": "<sender-user-id>",
    "message": {
      "id": "...",
      "chatroomId": "...",
      "senderUserId": "...",
      "content": "...",
      "createdAt": "2026-03-17T13:16:41.759"
    }
  }
}
```

---

## 4) Minimal frontend implementation (JavaScript)

```js
function connectUserSocket(jwt, onEvent) {
  const ws = new WebSocket(`ws://${location.host}/ws/chat?token=${encodeURIComponent(jwt)}`);

  ws.onmessage = (ev) => {
    try {
      const event = JSON.parse(ev.data);
      onEvent(event);
    } catch (e) {
      console.error("Invalid websocket JSON", e);
    }
  };

  return ws;
}

function connectRoomSocket(jwt, chatroomId, onEvent) {
  const url = `ws://${location.host}/ws/chat?token=${encodeURIComponent(jwt)}&chatroomId=${encodeURIComponent(chatroomId)}`;
  const ws = new WebSocket(url);

  ws.onmessage = (ev) => {
    try {
      const event = JSON.parse(ev.data);
      onEvent(event, chatroomId);
    } catch (e) {
      console.error("Invalid room websocket JSON", e);
    }
  };

  return ws;
}

// usage:
const roomSockets = new Map();

const userWs = connectUserSocket(jwt, (event) => {
  if (event.type === "newChatroom") {
    const chatroomId = event?.data?.chatroomId;
    if (!chatroomId) return;

    // 1) Add to chatroom list UI
    addChatroomToState(event.data);

    // 2) Open room socket if not yet opened
    if (!roomSockets.has(chatroomId)) {
      roomSockets.set(chatroomId, connectRoomSocket(jwt, chatroomId, handleRoomEvent));
    }
  }
});

function handleRoomEvent(event, chatroomId) {
  if (event.type === "newMessage") {
    appendMessage(chatroomId, event.data.message);
  } else if (event.type === "typing") {
    updateTyping(chatroomId, event.data);
  }
}
```

---

## 5) UI ordering (latest at bottom)

Render message arrays in ascending order by `createdAt` (oldest first, newest last).

Recommended frontend sort before rendering if needed:

```js
messages.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
```

This guarantees the latest message appears at the bottom.
