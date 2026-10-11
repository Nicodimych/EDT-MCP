/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateRulesResolver;
import com.ditrix.edt.mcp.server.utils.WorkmateRulesResolver.RulesResolutionException;

/**
 * Creates a local handle for a stateful 1C:Workmate conversation.
 * <p>
 * The handle (chatId) is all {@code workmate_chat_send} needs to keep landing messages in the
 * SAME cloud conversation. Creating a chat starts NO cloud conversation and sends nothing: the
 * first send does.
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
    private static final String KEY_RULES_SOURCE = "rulesSource"; //$NON-NLS-1$
    private static final String KEY_SKILLS = "skills"; //$NON-NLS-1$

    private final WorkmateChatStore store;
    private final WorkmateRulesResolver rulesResolver;

    public WorkmateChatCreateTool()
    {
        this(WorkmateChatStore.shared(), new WorkmateRulesResolver());
    }

    WorkmateChatCreateTool(WorkmateChatStore store)
    {
        this(store, new WorkmateRulesResolver());
    }

    WorkmateChatCreateTool(WorkmateChatStore store, WorkmateRulesResolver rulesResolver)
    {
        this.store = store;
        this.rulesResolver = rulesResolver;
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
            + "starts no cloud conversation and sends nothing - the first send does. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('workmate_chat_create')."; //$NON-NLS-1$
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
            .stringProperty(KEY_RULES_SOURCE,
                "Optional .workmate rules and skills source attached on the first send of this " //$NON-NLS-1$
                    + "chat: omit or pass 'none' to attach nothing (default); pass 'standard' " //$NON-NLS-1$
                    + "to load WORKMATE.md and the skill/command path catalogue from EDT's " //$NON-NLS-1$
                    + "standard hierarchy (~/.workmate, <workspace>/.workmate, " //$NON-NLS-1$
                    + "<project>/.workmate); or pass a custom directory path containing " //$NON-NLS-1$
                    + "WORKMATE.md and skills/.") //$NON-NLS-1$
            .stringArrayProperty(KEY_SKILLS,
                "Optional list of skill names (e.g. [\"formy\", \"samoproverka\"]) whose " //$NON-NLS-1$
                    + "SKILL.md content is eagerly inlined into the first send of this chat so " //$NON-NLS-1$
                    + "Workmate follows them immediately without spending Read tool rounds.") //$NON-NLS-1$
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
            .stringProperty("rulesSource", "The .workmate rules source bound to this chat, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("skills", "Default skill names bound to this chat, if any.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("note", "Usage note, e.g. when the cloud conversation starts.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = trimToNull(JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME));
        String skillName = trimToNull(JsonUtils.extractStringArgument(params, KEY_SKILL_NAME));
        String title = trimToNull(JsonUtils.extractStringArgument(params, KEY_TITLE));
        String rulesSource = WorkmateRulesResolver.normalizeRulesSource(
            JsonUtils.extractStringArgument(params, KEY_RULES_SOURCE));
        List<String> skills = WorkmateRulesResolver.normalizeSkills(
            JsonUtils.extractArrayArgument(params, KEY_SKILLS));

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

        if (rulesSource != null || !skills.isEmpty())
        {
            try
            {
                rulesResolver.resolvePreamble(rulesSource, skills, resolveOpenProject(projectName));
            }
            catch (RulesResolutionException e)
            {
                return ToolResult.error(e.getMessage()).toJson();
            }
        }

        WorkmateChatStore.ChatRecord chat =
            store.create(projectName, skillName, maxToolRounds, title, rulesSource, skills);
        return ToolResult.success()
            .put("chatId", chat.chatId()) //$NON-NLS-1$
            .put("projectName", chat.projectName()) //$NON-NLS-1$
            .put("skillName", chat.skillName()) //$NON-NLS-1$
            .put("maxToolRounds", chat.maxToolRounds()) //$NON-NLS-1$
            .put("title", chat.title()) //$NON-NLS-1$
            .put("rulesSource", chat.rulesSource()) //$NON-NLS-1$
            .put("skills", chat.skills()) //$NON-NLS-1$
            .put("note", "No cloud conversation exists yet: the first workmate_chat_send starts " //$NON-NLS-1$
                + "one. Chats and their cloud session cursors are persisted to workspace metadata.") //$NON-NLS-1$
            .toJson();
    }

    private static IProject resolveOpenProject(String projectName)
    {
        if (projectName == null)
        {
            return null;
        }
        try
        {
            ProjectContext context = ProjectContext.of(projectName);
            return context.exists() && context.isOpen() ? context.project() : null;
        }
        catch (Throwable ignored) // NOSONAR headless unit tests without an Eclipse workspace
        {
            return null;
        }
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
