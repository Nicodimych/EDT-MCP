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
 * Creates a local handle for a stateful 1C:Workmate conversation.
 * <p>
 * The handle (chatId) is all {@code workmate_chat_send} needs to keep landing messages in the
 * SAME cloud conversation. Creating a chat starts NO cloud conversation and sends nothing: the
 * first send does. The record lives in EDT's heap and is lost when EDT restarts.
 */
public class WorkmateChatCreateTool implements IMcpTool
{
    public static final String NAME = "workmate_chat_create"; //$NON-NLS-1$

    /** Generous default so multi-minute tasks do not hit Workmate's internal 10-round cap. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 80;

    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String KEY_SKILL_NAME = "skillName"; //$NON-NLS-1$
    private static final String KEY_MAX_TOOL_ROUNDS = "maxToolRounds"; //$NON-NLS-1$
    private static final String KEY_TITLE = "title"; //$NON-NLS-1$

    private final WorkmateChatStore store;

    public WorkmateChatCreateTool()
    {
        this(WorkmateChatStore.shared());
    }

    WorkmateChatCreateTool(WorkmateChatStore store)
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
        return "Create a local handle (chatId) for a STATEFUL conversation with 1C:Workmate: " //$NON-NLS-1$
            + "every workmate_chat_send with this chatId lands in the SAME cloud conversation, " //$NON-NLS-1$
            + "so Workmate keeps the context of everything said before. Creating the chat " //$NON-NLS-1$
            + "starts no cloud conversation and sends nothing - the first send does. The " //$NON-NLS-1$
            + "record lives in EDT's memory and is lost when EDT restarts. Parameters and " //$NON-NLS-1$
            + "examples: get_tool_guide('workmate_chat_create')."; //$NON-NLS-1$
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
            .stringProperty(KEY_PROJECT_NAME,
                "Optional open EDT project name used as Workmate's context for every send of " //$NON-NLS-1$
                    + "this chat. Omit only on Workmate builds with a default project context; " //$NON-NLS-1$
                    + "newer builds require it for conversations. Use list_projects to discover " //$NON-NLS-1$
                    + "valid names.") //$NON-NLS-1$
            .stringProperty(KEY_SKILL_NAME,
                "Optional Workmate skill name applied to every send. Omit to use '" //$NON-NLS-1$
                    + com.ditrix.edt.mcp.server.utils.WorkmateGateway.DEFAULT_SKILL
                    + "', the skill under which Workmate runs its own tool loop; 'raw' answers " //$NON-NLS-1$
                    + "from the model alone and inspects nothing.") //$NON-NLS-1$
            .integerProperty(KEY_MAX_TOOL_ROUNDS,
                "Optional positive limit for Workmate's internal tool-call rounds, applied to " //$NON-NLS-1$
                    + "every send of this chat unless overridden in workmate_chat_send; " //$NON-NLS-1$
                    + "defaults to " + DEFAULT_MAX_TOOL_ROUNDS + ".") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_TITLE,
                "Optional human-readable label for this chat, returned by workmate_chat_list.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "True when the chat was created.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("chatId", "The id to pass to the other workmate_chat_* tools.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "The project name this chat is bound to, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("skillName", "The Workmate skill this chat uses, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("maxToolRounds", "The default tool-round limit bound to this chat.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("title", "The human-readable label, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("note", "Usage note, e.g. when the cloud conversation starts.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = trimToNull(JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME));
        String skillName = trimToNull(JsonUtils.extractStringArgument(params, KEY_SKILL_NAME));
        String title = trimToNull(JsonUtils.extractStringArgument(params, KEY_TITLE));

        Integer maxToolRounds = Integer.valueOf(DEFAULT_MAX_TOOL_ROUNDS);
        if (params != null && params.containsKey(KEY_MAX_TOOL_ROUNDS))
        {
            int parsed = JsonUtils.extractIntArgument(params, KEY_MAX_TOOL_ROUNDS,
                Integer.MIN_VALUE);
            if (parsed <= 0)
            {
                return ToolResult.error("maxToolRounds must be a positive integer, but was '" //$NON-NLS-1$
                    + params.get(KEY_MAX_TOOL_ROUNDS)
                    + "'. Pass maxToolRounds>=1 or omit it, then retry workmate_chat_create.") //$NON-NLS-1$
                    .toJson();
            }
            maxToolRounds = Integer.valueOf(parsed);
        }

        WorkmateChatStore.ChatRecord chat =
            store.create(projectName, skillName, maxToolRounds, title);
        return ToolResult.success()
            .put("chatId", chat.chatId()) //$NON-NLS-1$
            .put("projectName", chat.projectName()) //$NON-NLS-1$
            .put("skillName", chat.skillName()) //$NON-NLS-1$
            .put("maxToolRounds", chat.maxToolRounds()) //$NON-NLS-1$
            .put("title", chat.title()) //$NON-NLS-1$
            .put("note", "No cloud conversation exists yet: the first workmate_chat_send starts " //$NON-NLS-1$
                + "one. The record lives in EDT's memory and is lost when EDT restarts.") //$NON-NLS-1$
            .toJson();
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
