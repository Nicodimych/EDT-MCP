# workmate_chat_drop

Forgets a stateful 1C:Workmate conversation: its journal and the cursor that continues it are
deleted from EDT's memory.

The CLOUD conversation is NOT deleted. Workmate keeps it, and it stays visible in the EDT chat
panel - only this plugin stops knowing how to continue it.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| chatId | yes | Chat id from `workmate_chat_create`. |

## Example

```json
{"chatId": "2d3f..."}
```

Result: `{"success": true, "dropped": "2d3f...", ...}`. A repeated call fails with
`Unknown chatId` - dropping is idempotent by effect, not by status.
