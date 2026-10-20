/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.ProgressListener;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;
import com.ditrix.edt.mcp.server.utils.WorkmateRulesResolver;
import com.ditrix.edt.mcp.server.utils.WorkmateRulesResolver.RulesResolutionException;

/**
 * Sends one user message into a stateful 1C:Workmate conversation as a background job.
 * <p>
 * The first send of a chat starts the cloud conversation; every later send re-enters it through
 * the stored {@code ConversationSession} cursor, so Workmate sees the whole history. The cursor
 * is advanced only after a turn that completed - a failed send never moves it.
 */
public class WorkmateChatSendTool implements IMcpTool
{
    public static final String NAME = "workmate_chat_send"; //$NON-NLS-1$

    static final int DEFAULT_TIMEOUT_SECONDS = 600;
    static final int MAX_TIMEOUT_SECONDS = 3600;

    /** Same discipline as ask_workmate: below the shared pool's size, so a delegated sub-job has a worker free. */
    static final int MAX_CONCURRENT_JOBS = 3;

    private static final String KEY_CHAT_ID = "chatId"; //$NON-NLS-1$
    private static final String KEY_MESSAGE = "message"; //$NON-NLS-1$
    private static final String KEY_TIMEOUT_SECONDS = "timeoutSeconds"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$
    private static final String KEY_SHARE_MCP_TOOLS = "shareMcpTools"; //$NON-NLS-1$
    private static final String KEY_MAX_TOOL_ROUNDS = "maxToolRounds"; //$NON-NLS-1$
    private static final String KEY_RULES_SOURCE = "rulesSource"; //$NON-NLS-1$
    private static final String KEY_SKILLS = "skills"; //$NON-NLS-1$

    private final WorkmateChatStore store;
    private final WorkmateGateway gateway;
    private final BackgroundJobs jobs;
    private final WorkmateRulesResolver rulesResolver;

    public WorkmateChatSendTool()
    {
        this(WorkmateChatStore.shared(), new WorkmateGateway(), BackgroundJobs.shared(),
            new WorkmateRulesResolver());
    }

    WorkmateChatSendTool(WorkmateChatStore store, WorkmateGateway gateway, BackgroundJobs jobs)
    {
        this(store, gateway, jobs, new WorkmateRulesResolver());
    }

    WorkmateChatSendTool(WorkmateChatStore store, WorkmateGateway gateway, BackgroundJobs jobs,
        WorkmateRulesResolver rulesResolver)
    {
        this.store = store;
        this.gateway = gateway;
        this.jobs = jobs;
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
        return "Send one message into a stateful 1C:Workmate conversation started with " //$NON-NLS-1$
            + "workmate_chat_create, as a background job returning its jobId. The first send " //$NON-NLS-1$
            + "starts the cloud conversation; later sends land in the SAME one, so Workmate " //$NON-NLS-1$
            + "keeps the full context. By default (shareMcpTools) Workmate may call EDT-MCP's " //$NON-NLS-1$
            + "own tools through this plugin, so it can READ the project and CHANGE code and " //$NON-NLS-1$
            + "metadata. Poll the job with get_job_status; read the finished exchange with " //$NON-NLS-1$
            + "workmate_chat_history. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('workmate_chat_send')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Same honest mark as ask_workmate: Workmate's tool loop can edit metadata and BSL.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE,
            Boolean.TRUE);
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_CHAT_ID,
                "Chat id returned by workmate_chat_create.", true) //$NON-NLS-1$
            .stringProperty(KEY_MESSAGE,
                "Non-empty message or instruction to send into this chat.", true) //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT_SECONDS,
                "Total wall-clock budget for this send across all get_job_status polls, in " //$NON-NLS-1$
                    + "seconds; defaults to " + DEFAULT_TIMEOUT_SECONDS + " and accepts 1 to " //$NON-NLS-1$ //$NON-NLS-2$
                    + MAX_TIMEOUT_SECONDS + ". After this budget the job is failed - unless " //$NON-NLS-1$
                    + "the request had already reached Workmate, which cannot be taken back: " //$NON-NLS-1$
                    + "the job then reports Workmate's own outcome rather than a retryable " //$NON-NLS-1$
                    + "timeout, because a retry would run the same work twice.") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Maximum time this call may wait for completion before returning its job " //$NON-NLS-1$
                    + "snapshot, in seconds; defaults to " + AskWorkmateTool.DEFAULT_WAIT_SECONDS //$NON-NLS-1$
                    + ", accepts 0 to " + AskWorkmateTool.MAX_WAIT_SECONDS //$NON-NLS-1$
                    + ". Use 0 to return immediately. This does not extend the job's total " //$NON-NLS-1$
                    + "timeoutSeconds budget.") //$NON-NLS-1$
            .booleanProperty(KEY_SHARE_MCP_TOOLS,
                "When true (default), the message is prefixed with instructions that let " //$NON-NLS-1$
                    + "Workmate call EDT-MCP's own tools through this plugin's in-process " //$NON-NLS-1$
                    + "bridge, so it can inspect the real project instead of answering from " //$NON-NLS-1$
                    + "general 1C knowledge.") //$NON-NLS-1$
            .integerProperty(KEY_MAX_TOOL_ROUNDS,
                "Optional positive limit for Workmate's internal tool-call rounds for THIS " //$NON-NLS-1$
                    + "send; overrides the chat's maxToolRounds (default " //$NON-NLS-1$
                    + WorkmateChatCreateTool.DEFAULT_MAX_TOOL_ROUNDS + ") when provided.") //$NON-NLS-1$
            .stringProperty(KEY_RULES_SOURCE,
                "Optional .workmate rules and skills source for THIS send: omit to inherit the " //$NON-NLS-1$
                    + "chat's rulesSource (injected automatically on the first send); pass " //$NON-NLS-1$
                    + "'none' to suppress rules; pass 'standard' to load WORKMATE.md and the " //$NON-NLS-1$
                    + "skill/command path catalogue from EDT's standard hierarchy (~/.workmate, " //$NON-NLS-1$
                    + "<workspace>/.workmate, <project>/.workmate); or pass a custom directory " //$NON-NLS-1$
                    + "path containing WORKMATE.md and skills/.") //$NON-NLS-1$
            .stringArrayProperty(KEY_SKILLS,
                "Optional list of skill names (e.g. [\"formy\", \"samoproverka\"]) whose " //$NON-NLS-1$
                    + "SKILL.md content is eagerly inlined into THIS send so Workmate follows " //$NON-NLS-1$
                    + "them immediately without spending Read tool rounds.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String chatId = trimToNull(JsonUtils.extractStringArgument(params, KEY_CHAT_ID));
        WorkmateChatStore.ChatRecord chat =
            chatId == null ? null : store.get(chatId);
        if (chat == null)
        {
            return ToolResult.error("Unknown chatId '" + chatId //$NON-NLS-1$
                + "'. Chats live in EDT's memory and are lost when EDT restarts. Create a new " //$NON-NLS-1$
                + "chat with workmate_chat_create, or list the current ones with " //$NON-NLS-1$
                + "workmate_chat_list, then retry workmate_chat_send.").toJson(); //$NON-NLS-1$
        }

        String message = trimToNull(JsonUtils.extractStringArgument(params, KEY_MESSAGE));
        if (message == null)
        {
            return ToolResult.error("message must contain non-whitespace text. Provide the " //$NON-NLS-1$
                + "message to send into chat " + chatId + " and retry workmate_chat_send.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }

        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            AskWorkmateTool.DEFAULT_WAIT_SECONDS, AskWorkmateTool.MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params != null ? params.get(KEY_WAIT_SECONDS) : null,
                AskWorkmateTool.DEFAULT_WAIT_SECONDS, AskWorkmateTool.MAX_WAIT_SECONDS);
        }

        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (params != null && params.containsKey(KEY_TIMEOUT_SECONDS))
        {
            Integer parsed = optionalPositiveInt(params, KEY_TIMEOUT_SECONDS);
            if (parsed == null || parsed.intValue() > MAX_TIMEOUT_SECONDS)
            {
                return ToolResult.error("timeoutSeconds must be an integer from 1 to " //$NON-NLS-1$
                    + MAX_TIMEOUT_SECONDS + ", but was '" + params.get(KEY_TIMEOUT_SECONDS) //$NON-NLS-1$
                    + "'. Pass a realistic bound or omit it to use the default of " //$NON-NLS-1$
                    + DEFAULT_TIMEOUT_SECONDS + " seconds.").toJson(); //$NON-NLS-1$
            }
            timeoutSeconds = parsed.intValue();
        }

        Integer effectiveMaxToolRounds = chat.maxToolRounds();
        if (params != null && params.containsKey(KEY_MAX_TOOL_ROUNDS))
        {
            Integer parsedRounds = optionalPositiveInt(params, KEY_MAX_TOOL_ROUNDS);
            if (parsedRounds == null)
            {
                return ToolResult.error("maxToolRounds must be a positive integer, but was '" //$NON-NLS-1$
                    + params.get(KEY_MAX_TOOL_ROUNDS)
                    + "'. Pass maxToolRounds>=1 or omit it to use the chat's limit, then " //$NON-NLS-1$
                    + "retry workmate_chat_send.").toJson(); //$NON-NLS-1$
            }
            effectiveMaxToolRounds = parsedRounds;
        }

        // One in-flight send per chat: two concurrent sends would race on the same cursor and
        // branch the conversation. The claim is taken BEFORE the job starts, so a refused send
        // never occupies a worker.
        if (!chat.beginSend())
        {
            return ToolResult.error("Chat " + chatId + " already has a send in flight. Poll " //$NON-NLS-1$ //$NON-NLS-2$
                + "that job with get_job_status; when it reaches a terminal state, read the " //$NON-NLS-1$
                + "result with workmate_chat_history and only then send the next message. Two " //$NON-NLS-1$
                + "concurrent sends into one chat would fork the cloud conversation.").toJson(); //$NON-NLS-1$
        }

        // Validate the project NOW, before the job exists: a chat created over a project that
        // has since been closed should fail fast instead of burning a worker.
        String projectName = chat.projectName();
        IProject project = null;
        if (projectName != null)
        {
            ProjectContext context = ProjectContext.of(projectName);
            if (!context.exists() || !context.isOpen())
            {
                chat.releaseSend();
                return ToolResult.error("Chat " + chatId + " is bound to project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
                    + "', which is no longer open in EDT. Open the project or create a new chat " //$NON-NLS-1$
                    + "without projectName, then retry workmate_chat_send.").toJson(); //$NON-NLS-1$
            }
            project = context.project();
        }

        final boolean firstTurn = chat.sessionState() == null;
        final String rawSendRulesSource = trimToNull(
            JsonUtils.extractStringArgument(params, KEY_RULES_SOURCE));
        final String effectiveRulesSource = rawSendRulesSource != null
            ? WorkmateRulesResolver.normalizeRulesSource(rawSendRulesSource)
            : chat.rulesSource();
        final boolean includeRulesAndCatalogue = rawSendRulesSource != null
            ? effectiveRulesSource != null
            : (firstTurn && effectiveRulesSource != null);
        final List<String> sendSkills = WorkmateRulesResolver.normalizeSkills(
            JsonUtils.extractArrayArgument(params, KEY_SKILLS));
        final List<String> effectiveSkills = firstTurn
            ? WorkmateRulesResolver.mergeSkills(chat.skills(), sendSkills)
            : sendSkills;

        final String rulesPreamble;
        try
        {
            rulesPreamble = rulesResolver.resolvePreamble(effectiveRulesSource,
                includeRulesAndCatalogue, effectiveSkills, project);
        }
        catch (RulesResolutionException e)
        {
            chat.releaseSend();
            return ToolResult.error(e.getMessage()).toJson();
        }

        final boolean shareMcpTools =
            JsonUtils.extractBooleanArgument(params, KEY_SHARE_MCP_TOOLS, true);
        final IProject jobProject = project;
        final String enrichedMessage = rulesPreamble + message;
        final String jobMessage = shareMcpTools
            ? AskWorkmateTool.mcpBridgePreamble(projectName) + enrichedMessage
            : enrichedMessage;
        final String jobTimeoutMessage = message;
        final int jobTimeoutSeconds = timeoutSeconds;
        final Integer jobMaxToolRounds = effectiveMaxToolRounds;
        try
        {
            JobSnapshot started = jobs.start(NAME,
                TimeUnit.SECONDS.toMillis(jobTimeoutSeconds), MAX_CONCURRENT_JOBS,
                "Accepted the chat message.", progress -> { //$NON-NLS-1$
                    ProgressListener listener = jobProgress(progress);
                    try
                    {
                        WorkmateResponse response = gateway.askWithSession(jobProject,
                            chat.sessionState(), jobMessage, jobMaxToolRounds,
                            chat.skillName(), remainingBudgetMillis(progress, jobTimeoutSeconds),
                            listener);
                        if (response == null || trimToNull(response.getText()) == null)
                        {
                            throw new WorkmateJobException("1C:Workmate returned an empty answer" //$NON-NLS-1$
                                + " for chat " + chat.chatId() + ". The message HAD been sent " //$NON-NLS-1$
                                + "and Workmate's tools change this project: inspect Workmate " //$NON-NLS-1$
                                + "and the project before sending it again."); //$NON-NLS-1$
                        }
                        // The journal is written here, inside the worker: it must exist even for
                        // a caller that never polls the job. The cursor advances only on this
                        // completed-turn path.
                        chat.completeTurn(jobTimeoutMessage, response.getText(),
                            response.getSessionState(), response.isDeclaredFinal(),
                            response.getContinuations());
                        return response;
                    }
                    catch (GatewayException e)
                    {
                        String actionable = AskWorkmateTool.actionableMessage(e,
                            jobTimeoutSeconds);
                        chat.failTurn(jobTimeoutMessage, actionable);
                        throw new WorkmateJobException(actionable, e);
                    }
                    finally
                    {
                        chat.releaseSend();
                    }
                });
            if (started == null)
            {
                chat.releaseSend();
                return ToolResult.error(NAME + " already has " + MAX_CONCURRENT_JOBS //$NON-NLS-1$
                    + " jobs running, which is the limit. Poll a running job with " //$NON-NLS-1$
                    + "get_job_status and act on its answer before sending another message.") //$NON-NLS-1$
                    .toJson();
            }
            chat.setLastJobId(started.getId());
            return BackgroundJobRenderer.render(BackgroundJobPolling.await(jobs,
                started.getId(), waitSeconds.intValue()));
        }
        catch (RejectedExecutionException e)
        {
            chat.releaseSend();
            return ToolResult.error(
                "Could not start workmate_chat_send because the background-job registry is " //$NON-NLS-1$
                    + "full or stopping: " + e.getMessage() + ". Poll existing jobs with " //$NON-NLS-1$ //$NON-NLS-2$
                    + "get_job_status and retry, or restart EDT if the bundle is stopping.") //$NON-NLS-1$
                .toJson();
        }
    }

    private static long remainingBudgetMillis(ProgressReporter progress, int timeoutSeconds)
    {
        long total = TimeUnit.SECONDS.toMillis(timeoutSeconds);
        return Math.min(total, Math.max(0L, progress.remainingMillis()));
    }

    private static ProgressListener jobProgress(ProgressReporter progress)
    {
        return new ProgressListener()
        {
            @Override
            public void onProgress(String message)
            {
                progress.add(message);
            }

            @Override
            public boolean onTryCommit()
            {
                return progress.tryCommit();
            }
        };
    }

    private static Integer optionalPositiveInt(Map<String, String> params, String key)
    {
        if (params == null || !params.containsKey(key))
        {
            return null;
        }
        int value = JsonUtils.extractIntArgument(params, key, Integer.MIN_VALUE);
        return value > 0 ? Integer.valueOf(value) : null;
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

    private static final class WorkmateJobException extends Exception
    {
        private static final long serialVersionUID = 1L;

        WorkmateJobException(String message)
        {
            super(message);
        }

        WorkmateJobException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
