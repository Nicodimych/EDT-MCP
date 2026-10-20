/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;

/**
 * Forgets a stateful 1C:Workmate conversation. The CLOUD conversation is NOT deleted: Workmate
 * keeps it and it stays visible in the EDT chat panel - only this plugin stops remembering how
 * to continue it.
 */
public class WorkmateChatDropTool implements IMcpTool
{
    public static final String NAME = "workmate_chat_drop"; //$NON-NLS-1$

    private static final String KEY_CHAT_ID = "chatId"; //$NON-NLS-1$

    private final WorkmateChatStore store;

    public WorkmateChatDropTool()
    {
        this(WorkmateChatStore.shared());
    }

    WorkmateChatDropTool(WorkmateChatStore store)
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
        return "Forget a stateful 1C:Workmate conversation: its journal and the cursor that " //$NON-NLS-1$
            + "continues it are deleted from EDT's memory. The CLOUD conversation is NOT " //$NON-NLS-1$
            + "deleted - it stays in Workmate and in the EDT chat panel; only this plugin " //$NON-NLS-1$
            + "stops knowing how to continue it."; //$NON-NLS-1$
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
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the chat was forgotten.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("dropped", "The chat id that was dropped.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("note", "Clarifies that the cloud conversation still exists.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String chatId = JsonUtils.extractStringArgument(params, KEY_CHAT_ID);
        if (chatId == null || chatId.trim().isEmpty())
        {
            return ToolResult.error("chatId must contain a non-empty chat id. Pass the id " //$NON-NLS-1$
                + "returned by workmate_chat_create, then retry workmate_chat_drop.").toJson(); //$NON-NLS-1$
        }
        if (!store.drop(chatId.trim()))
        {
            return ToolResult.error("Unknown chatId '" + chatId //$NON-NLS-1$
                + "'. Nothing was forgotten. List the current chats with workmate_chat_list, " //$NON-NLS-1$
                + "then retry with a known id.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("dropped", chatId.trim()) //$NON-NLS-1$
            .put("note", "The cloud conversation still exists in Workmate and in the EDT chat " //$NON-NLS-1$
                + "panel; only this plugin's record of it was deleted.") //$NON-NLS-1$
            .toJson();
    }
}
