/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.ChatRecord;

/** Lists the stateful 1C:Workmate conversations this EDT currently remembers. */
public class WorkmateChatListTool implements IMcpTool
{
    public static final String NAME = "workmate_chat_list"; //$NON-NLS-1$

    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$

    private final WorkmateChatStore store;

    public WorkmateChatListTool()
    {
        this(WorkmateChatStore.shared());
    }

    WorkmateChatListTool(WorkmateChatStore store)
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
        return "List the stateful 1C:Workmate conversations this EDT currently remembers, " //$NON-NLS-1$
            + "most recently updated first. Read-only. Chats live in EDT's memory and are " //$NON-NLS-1$
            + "lost when EDT restarts - an empty list after a restart is expected, not an error."; //$NON-NLS-1$
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
            .integerProperty(KEY_LIMIT,
                "Optional maximum number of chats to return; omit to return every remembered " //$NON-NLS-1$
                    + "chat (at most 50).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the list was read.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("chats", //$NON-NLS-1$
                "Known chats, most recently updated first: {chatId, title, projectName, " //$NON-NLS-1$
                    + "skillName, turns, lastTurnStatus (none/running/done/failed), hasUnread, " //$NON-NLS-1$
                    + "lastJobId, activeJobId, hasSessionCursor, sendInFlight, " //$NON-NLS-1$
                    + "createdAtMillis, updatedAtMillis}.") //$NON-NLS-1$
            .stringProperty("note", "Usage note about chat persistence.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        Integer limit = null;
        if (params != null && params.containsKey(KEY_LIMIT))
        {
            int parsed = JsonUtils.extractIntArgument(params, KEY_LIMIT, Integer.MIN_VALUE);
            if (parsed <= 0)
            {
                return ToolResult.error("limit must be a positive integer, but was '" //$NON-NLS-1$
                    + params.get(KEY_LIMIT)
                    + "'. Pass limit>=1 or omit it, then retry workmate_chat_list.") //$NON-NLS-1$
                    .toJson();
            }
            limit = Integer.valueOf(parsed);
        }

        List<ChatRecord> chats = store.list(limit == null ? 0 : limit.intValue());
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ChatRecord chat : chats)
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("chatId", chat.chatId()); //$NON-NLS-1$
            entry.put("title", chat.title()); //$NON-NLS-1$
            entry.put("projectName", chat.projectName()); //$NON-NLS-1$
            entry.put("skillName", chat.skillName()); //$NON-NLS-1$
            entry.put("turns", Integer.valueOf(chat.turns().size())); //$NON-NLS-1$
            entry.put("lastTurnStatus", chat.lastTurnStatus()); //$NON-NLS-1$
            entry.put("hasUnread", Boolean.valueOf(chat.hasUnread())); //$NON-NLS-1$
            entry.put("lastJobId", chat.lastJobId()); //$NON-NLS-1$
            entry.put("activeJobId", chat.activeJobId()); //$NON-NLS-1$
            entry.put("hasSessionCursor", Boolean.valueOf(chat.sessionState() != null)); //$NON-NLS-1$
            entry.put("sendInFlight", Boolean.valueOf(chat.sendInFlight())); //$NON-NLS-1$
            entry.put("createdAtMillis", Long.valueOf(chat.createdAtMillis())); //$NON-NLS-1$
            entry.put("updatedAtMillis", Long.valueOf(chat.updatedAtMillis())); //$NON-NLS-1$
            rendered.add(entry);
        }

        return ToolResult.success()
            .put("chats", rendered) //$NON-NLS-1$
            .put("note", "Chats and their cloud session cursors are persisted to workspace metadata.") //$NON-NLS-1$
            .toJson();
    }
}
