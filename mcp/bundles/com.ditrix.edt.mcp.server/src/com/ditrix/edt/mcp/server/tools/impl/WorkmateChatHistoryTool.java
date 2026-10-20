/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.ChatRecord;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.TurnRecord;

/** Reads the journal of a stateful 1C:Workmate conversation. */
public class WorkmateChatHistoryTool implements IMcpTool
{
    public static final String NAME = "workmate_chat_history"; //$NON-NLS-1$

    private static final String KEY_CHAT_ID = "chatId"; //$NON-NLS-1$
    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$

    private final WorkmateChatStore store;

    public WorkmateChatHistoryTool()
    {
        this(WorkmateChatStore.shared());
    }

    WorkmateChatHistoryTool(WorkmateChatStore store)
    {
        this.store = store;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read the journal of a stateful 1C:Workmate conversation created with " //$NON-NLS-1$
            + "workmate_chat_create: the messages sent, Workmate's finished answers and the " //$NON-NLS-1$
            + "errors, oldest first. The journal is written when a send COMPLETES, so poll the " //$NON-NLS-1$
            + "send's jobId with get_job_status before reading the newest exchange. Read-only."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_CHAT_ID,
                "Chat id returned by workmate_chat_create.", true) //$NON-NLS-1$
            .integerProperty(KEY_LIMIT,
                "Optional maximum number of journal entries to return, counting from the " //$NON-NLS-1$
                    + "NEWEST; omit to return the whole journal (capped at 200 entries per " //$NON-NLS-1$
                    + "chat).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the journal was read.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("chatId", "The chat the journal belongs to.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("title", "The chat's human-readable label, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "The project name the chat is bound to, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("turns", //$NON-NLS-1$
                "Journal entries, oldest first, with ascending 1-based seq (1..N): " //$NON-NLS-1$
                    + "{seq, role (user/assistant/error), text, atMillis, error, " //$NON-NLS-1$
                    + "declaredFinal, continuations}.") //$NON-NLS-1$
            .booleanProperty("hasSessionCursor", //$NON-NLS-1$
                "True when a conversation cursor exists and the chat can be continued.") //$NON-NLS-1$
            .booleanProperty("sendInFlight", "True while a send of this chat is running.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("updatedAtMillis", "Last write to the journal.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String chatId = trimToNull(JsonUtils.extractStringArgument(params, KEY_CHAT_ID));
        ChatRecord chat = chatId == null ? null : store.get(chatId);
        if (chat == null)
        {
            return unknownChatError(chatId);
        }

        List<TurnRecord> turns = chat.turns();
        int totalTurns = turns.size();
        Integer limit = null;
        if (params != null && params.containsKey(KEY_LIMIT))
        {
            int parsed = JsonUtils.extractIntArgument(params, KEY_LIMIT, Integer.MIN_VALUE);
            if (parsed <= 0)
            {
                return ToolResult.error("limit must be a positive integer, but was '" //$NON-NLS-1$
                    + params.get(KEY_LIMIT)
                    + "'. Pass limit>=1 or omit it, then retry workmate_chat_history.") //$NON-NLS-1$
                    .toJson();
            }
            limit = Integer.valueOf(parsed);
        }
        if (limit != null && turns.size() > limit.intValue())
        {
            turns = new ArrayList<>(turns.subList(turns.size() - limit.intValue(), turns.size()));
        }
        chat.markRead();

        List<Map<String, Object>> rendered = new ArrayList<>();
        int seq = totalTurns - turns.size() + 1;
        for (TurnRecord turn : turns)
        {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("seq", Integer.valueOf(seq++)); //$NON-NLS-1$
            entry.put("role", turn.role()); //$NON-NLS-1$
            entry.put("text", turn.text()); //$NON-NLS-1$
            entry.put("atMillis", Long.valueOf(turn.atMillis())); //$NON-NLS-1$
            entry.put("error", Boolean.valueOf(turn.isError())); //$NON-NLS-1$
            entry.put("declaredFinal", Boolean.valueOf(turn.isDeclaredFinal())); //$NON-NLS-1$
            entry.put("continuations", Integer.valueOf(turn.continuations())); //$NON-NLS-1$
            rendered.add(entry);
        }

        return ToolResult.success()
            .put("chatId", chat.chatId()) //$NON-NLS-1$
            .put("title", chat.title()) //$NON-NLS-1$
            .put("projectName", chat.projectName()) //$NON-NLS-1$
            .put("turns", rendered) //$NON-NLS-1$
            .put("hasSessionCursor", Boolean.valueOf(chat.sessionState() != null)) //$NON-NLS-1$
            .put("sendInFlight", Boolean.valueOf(chat.sendInFlight())) //$NON-NLS-1$
            .put("updatedAtMillis", Long.valueOf(chat.updatedAtMillis())) //$NON-NLS-1$
            .toJson();
    }

    static String unknownChatError(String chatId)
    {
        return ToolResult.error("Unknown chatId '" + chatId //$NON-NLS-1$
            + "'. Chats live in EDT's memory and are lost when EDT restarts. Create a new " //$NON-NLS-1$
            + "chat with workmate_chat_create, or list the current ones with " //$NON-NLS-1$
            + "workmate_chat_list, then retry.").toJson(); //$NON-NLS-1$
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
