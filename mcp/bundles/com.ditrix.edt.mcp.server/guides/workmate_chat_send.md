# workmate_chat_send

Sends one message into a stateful 1C:Workmate conversation created with
`workmate_chat_create`, as a background job. The call returns a job snapshot; poll the jobId
with `get_job_status` until it reaches a terminal state, then read the finished exchange with
`workmate_chat_history`.

The first send of a chat starts the cloud conversation; every later send re-enters the SAME
one through the stored conversation cursor, so Workmate sees the whole history.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| chatId | yes | Chat id from `workmate_chat_create`. |
| message | yes | Non-empty message or instruction. |
| timeoutSeconds | — | Total wall-clock budget for this send across all polls (default 600, max 3600). After the budget the job is failed — unless the request had already reached Workmate, which cannot be taken back: the job then reports Workmate's own outcome, because a retry would run the same work twice. |
| waitSeconds | — | How long THIS call waits before returning the job snapshot (default 5, 0–45). Does not extend timeoutSeconds. |
| shareMcpTools | — | Default true: prefix the message with instructions that let Workmate call EDT-MCP's own tools through the in-process bridge, so it can inspect the real project. |
| maxToolRounds | — | Optional positive limit for Workmate's internal tool-call rounds for THIS send; overrides the chat's `maxToolRounds` (default `80`) when provided. |
| rulesSource | — | Optional `.workmate` rules/skills source for THIS send: omit to inherit the chat's `rulesSource` (injected on the first send); `"none"` to suppress; `"standard"` for EDT's hierarchy (`~/.workmate`, `<workspace>/.workmate`, `<project>/.workmate`); or a custom directory path. |
| skills | — | Optional array of skill names (e.g. `["formy", "samoproverka"]`) whose `SKILL.md` content is eagerly inlined into THIS send. |

## Examples

Send and return immediately:

```json
{
  "chatId": "2d3f...",
  "message": "List the catalogs of this configuration and their purpose",
  "waitSeconds": 0
}
```

Poll, then read the answer:

```json
{"tool": "get_job_status", "arguments": {"jobId": "<id from send>", "waitSeconds": 45}}
```

```json
{"tool": "workmate_chat_history", "arguments": {"chatId": "2d3f...", "limit": 2}}
```

Then continue the SAME conversation with a follow-up — no context needs to be repeated:

```json
{
  "chatId": "2d3f...",
  "message": "Now detail the largest one"
}
```

## Rules

- ONE send per chat at a time: a second send while one is running is refused, because two
  in-flight sends would fork the cloud conversation. Poll the running job instead.
- The conversation cursor advances only after a send that COMPLETED. A failed send never
  moves it; the journal records the error.
- Workmate's tool loop can READ and CHANGE the project (metadata, BSL) when shareMcpTools is
  on. A timeout does not undo changes already made — inspect the project before retrying.
