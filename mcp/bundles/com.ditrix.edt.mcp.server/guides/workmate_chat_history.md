# workmate_chat_history

Reads the journal of a stateful 1C:Workmate conversation created with
`workmate_chat_create`: messages sent, Workmate's finished answers and errors, oldest first.

The journal is written when a send COMPLETES, not when it starts. Poll the send's jobId with
`get_job_status` before expecting the newest exchange to appear here.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| chatId | yes | Chat id from `workmate_chat_create`. |
| limit | — | Maximum number of entries to return, counting from the NEWEST; omit for the whole journal (capped at 200 per chat). |

## Example

```json
{"chatId": "2d3f...", "limit": 2}
```

Result:

```json
{
  "success": true,
  "chatId": "2d3f...",
  "turns": [
    {"seq": 3, "role": "user", "text": "Now detail the largest one", ...},
    {"seq": 4, "role": "assistant", "text": "...", "declaredFinal": true, ...}
  ],
  "hasSessionCursor": true,
  "sendInFlight": false
}
```

`turns` are ordered oldest first, and `seq` is the ascending 1-based position (`1..N`) in the
journal (so with 4 total entries and `limit: 2`, the returned slice has `seq` `3` and `4`).
Reading history also clears `hasUnread` reported by `workmate_chat_list`.

`role` is `user`, `assistant` or `error`. An `error` turn means the send failed; the
conversation cursor was NOT advanced by that attempt, so the chat can be continued from the
last confirmed point.
