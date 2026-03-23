# Chatroom List Client Update (DM metadata)

## Backend change summary

`GET /api/chat/chatrooms?page=0&size=20` now includes DM-specific fields in each chatroom item.

For `type = "DIRECT"`, the API-gateway resolves the *other participant* and returns:

- `otherUserId`
- `otherUserName` (derived from profile first/last name; falls back to userId)
- `otherUserProfileImageFileId` (profile image file id)

For non-DM chatrooms (e.g., `AI`), these fields are `null`.

## New response shape

```json
{
  "chatrooms": [
    {
      "id": "3f39f1e5-88ab-4e6a-bc6c-7f1d8c68b95f",
      "type": "DIRECT",
      "memberIds": [
        "me-user-id",
        "other-user-id"
      ],
      "createdAt": "2026-03-21T12:10:25.014",
      "otherUserId": "other-user-id",
      "otherUserName": "John Doe",
      "otherUserProfileImageFileId": "2f4a0f0f-4f3f-4b53-aac4-6f5f5b5f7a11"
    }
  ],
  "total": 1
}
```

## Client-side instructions

1. Keep using `GET /api/chat/chatrooms` as before.
2. When rendering a chatroom row/card:
   - If `type === "DIRECT"`, use `otherUserName` as title.
   - If `type === "DIRECT"`, load avatar from `otherUserProfileImageFileId` via your existing file endpoint.
   - Fallbacks:
     - if `otherUserName` is null, show `otherUserId`
     - if `otherUserProfileImageFileId` is null, show default avatar
3. For `type !== "DIRECT"`, continue existing rendering logic.
4. Keep `memberIds` usage unchanged for access checks or participant lists.

## Compatibility note

This is a backward-compatible extension of the chatroom object. Existing clients that ignore unknown fields continue to work.
