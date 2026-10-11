# workmate_chat_list

Lists the stateful 1C:Workmate conversations this EDT currently remembers, most recently
updated first. Read-only.

Chats live in EDT's memory and are lost when EDT restarts - an empty list after a restart is
expected, not an error.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| limit | — | Maximum number of chats to return; omit for all (at most 50). |

## Example

```json
{}
```

Result:

```json
{
  "success": true,
  "chats": [
    {
      "chatId": "2d3f...",
      "title": "Refactoring research",
      "projectName": "MyConfiguration",
      "turns": 4,
      "lastTurnStatus": "done",
      "hasUnread": true,
      "hasSessionCursor": true,
      "sendInFlight": false,
      "createdAtMillis": 1760000000000,
      "updatedAtMillis": 1760000600000
    }
  ]
}
```

- `lastTurnStatus` is `"none"` (before any send finishes), `"running"` (while a send is in
  flight), `"done"` (last turn produced an answer), or `"failed"` (last turn errored).
- `hasUnread: true` means a turn completed or failed since the last `workmate_chat_history`
  call on this chat.
- `sendInFlight: true` means a `workmate_chat_send` is still running - poll its jobId with
  `get_job_status` instead of sending again.
