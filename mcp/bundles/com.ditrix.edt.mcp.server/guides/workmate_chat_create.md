# workmate_chat_create

Creates a local handle (chatId) for a STATEFUL conversation with 1C:Workmate. Every
`workmate_chat_send` with this chatId lands in the SAME cloud conversation, so Workmate keeps
the context of everything said before — unlike `ask_workmate`, where every call starts a new
conversation.

Creating the chat starts NO cloud conversation and sends nothing. The record lives in EDT's
memory and is lost when EDT restarts.

## Parameters

| Parameter | Required | Description |
| --- | --- | --- |
| projectName | — | Open EDT project used as Workmate's context for every send of this chat. Newer Workmate builds require it for conversations. |
| skillName | — | Workmate skill for every send. Omit to use `custom` (Workmate's own tool loop); `raw` answers from the model alone. |
| maxToolRounds | — | Positive limit for Workmate's internal tool-call rounds, applied to every send unless overridden in `workmate_chat_send` (default `80`). |
| title | — | Human-readable label, returned by `workmate_chat_list`. |
| rulesSource | — | Optional `.workmate` rules/skills source attached on the first send of this chat: omit or `"none"` to attach nothing; `"standard"` to load `WORKMATE.md` and the skill/command path catalogue from `~/.workmate`, `<workspace>/.workmate`, and `<project>/.workmate`; or a custom directory path. |
| skills | — | Optional array of skill names (e.g. `["formy", "samoproverka"]`) whose `SKILL.md` content is eagerly inlined into the first send of this chat. |

## Examples

Create a chat over an open project with standard `.workmate` rules and eager skills:

```json
{
  "projectName": "MyConfiguration",
  "title": "Wave 11 - Forms",
  "rulesSource": "standard",
  "skills": ["formy", "samoproverka"]
}
```

Result: `{"success": true, "chatId": "...", ...}`. Pass that `chatId` to
`workmate_chat_send`.

## Typical flow

1. `workmate_chat_create` → chatId.
2. `workmate_chat_send` (chatId, message) → jobId; poll with `get_job_status`.
3. `workmate_chat_history` (chatId) → the finished exchange.
4. Repeat 2–3: every send continues the same cloud conversation.

See `workmate_chat_send` for the full loop.
