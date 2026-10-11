/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.GatewayException;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.ProgressListener;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;

/**
 * Tests for {@link WorkmateChatSendTool} - the stateful 1C:Workmate conversation send surface.
 * The load-bearing contract: every send after the first re-enters the SAME cloud conversation
 * through the stored cursor, and the cursor advances only when a turn completes.
 */
public class WorkmateChatSendToolTest
{
    private final List<Harness> harnesses = new ArrayList<>();

    @After
    public void closeHarnesses()
    {
        for (Harness harness : harnesses)
        {
            harness.close();
        }
    }

    private Harness harness(WorkmateChatStore store, WorkmateGateway gateway)
    {
        Harness harness = new Harness(store, gateway);
        harnesses.add(harness);
        return harness;
    }

    @Test
    public void testSendToUnknownChatIsActionable()
    {
        Harness harness = harness(new WorkmateChatStore(), new WorkmateGateway());

        String result = harness.sendTool.execute(paramsOf("chatId", "no-such-chat", //$NON-NLS-1$ //$NON-NLS-2$
            "message", "hello")); //$NON-NLS-1$ //$NON-NLS-2$

        assertErrorContains(result, "Unknown chatId", "workmate_chat_create"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSendRequiresAMessage()
    {
        Harness harness = harness(new WorkmateChatStore(), new WorkmateGateway());
        String chatId = harness.createChat(null);

        String result =
            harness.sendTool.execute(paramsOf("chatId", chatId, "message", "  ")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertErrorContains(result, "message must contain non-whitespace text"); //$NON-NLS-1$
    }

    @Test
    public void testSuccessfulSendContinuesThroughTheStoredCursor()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        AtomicReference<String> firstCursorIn = new AtomicReference<>();
        AtomicReference<String> secondCursorIn = new AtomicReference<>();
        AtomicReference<String> secondMessage = new AtomicReference<>();
        WorkmateGateway gateway = new WorkmateGateway()
        {
            @Override
            public WorkmateResponse askWithSession(IProject project, String sessionState,
                String question, Integer maxToolRounds, String skillName, long timeoutMillis,
                ProgressListener progress)
            {
                if (sessionState == null)
                {
                    firstCursorIn.set(null);
                    return new WorkmateResponse("answer one", null, null, 0, true, false, true, //$NON-NLS-1$
                        "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}"); //$NON-NLS-1$
                }
                secondCursorIn.set(sessionState);
                secondMessage.set(question);
                return new WorkmateResponse("answer two", null, null, 0, true, false, true, //$NON-NLS-1$
                    "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u2\"}"); //$NON-NLS-1$
            }
        };
        Harness harness = harness(store, gateway);
        String chatId = harness.createChat(null);

        String first = harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "question one")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertJobStatus(first, "done"); //$NON-NLS-1$
        assertNull("the first send starts a NEW conversation (no cursor in)", //$NON-NLS-1$
            firstCursorIn.get());
        assertEquals("the completed turn's cursor is stored", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$
            store.get(chatId).sessionState());

        String second = harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "question two")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertJobStatus(second, "done"); //$NON-NLS-1$
        assertEquals("the second send re-enters the SAME conversation through the cursor", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", secondCursorIn.get()); //$NON-NLS-1$
        assertTrue("the bridge preamble is prepended by default, the question preserved", //$NON-NLS-1$
            secondMessage.get() != null && secondMessage.get().endsWith("question two")); //$NON-NLS-1$
        assertEquals("the cursor advances to the newest confirmed turn", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u2\"}", //$NON-NLS-1$
            store.get(chatId).sessionState());
    }

    @Test
    public void testFailedSendKeepsTheCursorAndRecordsTheError()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateGateway gateway = new WorkmateGateway()
        {
            @Override
            public WorkmateResponse askWithSession(IProject project, String sessionState,
                String question, Integer maxToolRounds, String skillName, long timeoutMillis,
                ProgressListener progress) throws GatewayException
            {
                if (sessionState == null)
                {
                    return new WorkmateResponse("answer one", null, null, 0, true, false, true, //$NON-NLS-1$
                        "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}"); //$NON-NLS-1$
                }
                throw GatewayException.notReady("the token expired"); //$NON-NLS-1$
            }
        };
        Harness harness = harness(store, gateway);
        String chatId = harness.createChat(null);
        harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "question one")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String failed = harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "question two")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertJobStatus(failed, "failed"); //$NON-NLS-1$

        assertEquals("a failed send never moves the cursor", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$
            store.get(chatId).sessionState());
        String history =
            new WorkmateChatHistoryTool(store).execute(paramsOf("chatId", chatId)); //$NON-NLS-1$
        assertTrue(history.contains("\"role\":\"error\"")); //$NON-NLS-1$
        assertTrue(history.contains("the token expired")); //$NON-NLS-1$
    }

    @Test
    public void testConcurrentSendIntoTheSameChatIsRefused() throws Exception
    {
        WorkmateChatStore store = new WorkmateChatStore();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkmateGateway gateway = new WorkmateGateway()
        {
            @Override
            public WorkmateResponse askWithSession(IProject project, String sessionState,
                String question, Integer maxToolRounds, String skillName, long timeoutMillis,
                ProgressListener progress)
            {
                entered.countDown();
                try
                {
                    release.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return new WorkmateResponse("answer", null); //$NON-NLS-1$
            }
        };
        Harness harness = harness(store, gateway);
        String chatId = harness.createChat(null);

        Map<String, String> firstParams = paramsOf("chatId", chatId, "message", "one"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        firstParams.put("waitSeconds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String firstResult = harness.sendTool.execute(firstParams);
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        String refused = harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "two")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertErrorContains(refused, "already has a send in flight", "get_job_status"); //$NON-NLS-1$ //$NON-NLS-2$

        release.countDown();
        assertJobStatus(harness.pollJob(extractJobId(firstResult), 5), "done"); //$NON-NLS-1$
        assertFalse(store.get(chatId).sendInFlight());
    }

    @Test
    public void testSendUsesDefaultAndAllowsPerSendMaxToolRoundsOverride()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        AtomicReference<Integer> roundsSeen = new AtomicReference<>();
        WorkmateGateway gateway = new WorkmateGateway()
        {
            @Override
            public WorkmateResponse askWithSession(IProject project, String sessionState,
                String question, Integer maxToolRounds, String skillName, long timeoutMillis,
                ProgressListener progress)
            {
                roundsSeen.set(maxToolRounds);
                return new WorkmateResponse("answer", null, null, 0, true, false, true, //$NON-NLS-1$
                    "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}"); //$NON-NLS-1$
            }
        };
        Harness harness = harness(store, gateway);
        String chatId = harness.createChat(null);

        String first = harness.sendTool.execute(
            paramsOf("chatId", chatId, "message", "default rounds")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertJobStatus(first, "done"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(WorkmateChatCreateTool.DEFAULT_MAX_TOOL_ROUNDS),
            roundsSeen.get());

        Map<String, String> overrideParams =
            paramsOf("chatId", chatId, "message", "heavy turn"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        overrideParams.put("maxToolRounds", "120"); //$NON-NLS-1$ //$NON-NLS-2$
        String second = harness.sendTool.execute(overrideParams);
        assertJobStatus(second, "done"); //$NON-NLS-1$
        assertEquals(Integer.valueOf(120), roundsSeen.get());

        Map<String, String> badParams =
            paramsOf("chatId", chatId, "message", "invalid rounds"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        badParams.put("maxToolRounds", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        String rejected = harness.sendTool.execute(badParams);
        assertErrorContains(rejected, "maxToolRounds must be a positive integer"); //$NON-NLS-1$
    }

    /** Bundles the chat tools over one store and one job registry for a test. */
    static final class Harness implements AutoCloseable
    {
        final WorkmateChatStore store;
        final BackgroundJobs jobs;
        final WorkmateChatCreateTool createTool;
        final WorkmateChatSendTool sendTool;

        Harness(WorkmateChatStore store, WorkmateGateway gateway)
        {
            this.store = store;
            this.jobs = new BackgroundJobs(20, 2);
            this.createTool = new WorkmateChatCreateTool(store);
            this.sendTool = new WorkmateChatSendTool(store, gateway, jobs);
        }

        @Override
        public void close()
        {
            jobs.close();
        }

        String createChat(String projectName)
        {
            Map<String, String> params = new HashMap<>();
            if (projectName != null)
            {
                params.put("projectName", projectName); //$NON-NLS-1$
            }
            String result = createTool.execute(params);
            assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
            return extractJsonString(result, "chatId"); //$NON-NLS-1$
        }

        String pollJob(String jobId, int waitSeconds)
        {
            Map<String, String> params = new HashMap<>();
            params.put("jobId", jobId); //$NON-NLS-1$
            params.put("waitSeconds", Integer.toString(waitSeconds)); //$NON-NLS-1$
            return new GetJobStatusTool(jobs).execute(params);
        }
    }

    static Map<String, String> paramsOf(String key1, String value1, String key2, String value2)
    {
        Map<String, String> params = new HashMap<>();
        params.put(key1, value1);
        params.put(key2, value2);
        return params;
    }

    static Map<String, String> paramsOf(String key, String value)
    {
        Map<String, String> params = new HashMap<>();
        params.put(key, value);
        return params;
    }

    static String extractJsonString(String json, String key)
    {
        String needle = "\"" + key + "\":\""; //$NON-NLS-1$ //$NON-NLS-2$
        int at = json.indexOf(needle);
        assertTrue("Expected key '" + key + "' in: " + json, at >= 0); //$NON-NLS-1$ //$NON-NLS-2$
        int from = at + needle.length();
        int end = json.indexOf('"', from);
        assertTrue(end > from);
        return json.substring(from, end);
    }

    static String extractJobId(String markdown)
    {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\| jobId \\| ([^|]+) \\|") //$NON-NLS-1$
            .matcher(markdown);
        assertTrue("Expected a jobId row in: " + markdown, matcher.find()); //$NON-NLS-1$
        return matcher.group(1).trim();
    }

    static void assertJobStatus(String markdown, String status)
    {
        assertNotNull(markdown);
        assertTrue("Expected status '" + status + "' in: " + markdown, //$NON-NLS-1$ //$NON-NLS-2$
            markdown.startsWith("# Background job: " + status)); //$NON-NLS-1$
    }

    static void assertErrorContains(String json, String... fragments)
    {
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false")); //$NON-NLS-1$
        for (String fragment : fragments)
        {
            assertTrue("Expected '" + fragment + "' in: " + json, json.contains(fragment)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertFalse("An actionable error must not leak a stack trace: " + json, //$NON-NLS-1$
            json.contains("\tat ")); //$NON-NLS-1$
    }
}
