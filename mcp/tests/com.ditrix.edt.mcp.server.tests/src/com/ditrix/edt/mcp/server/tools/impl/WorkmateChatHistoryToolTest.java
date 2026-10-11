/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.ChatRecord;

/** Tests for {@link WorkmateChatHistoryTool}. */
public class WorkmateChatHistoryToolTest
{
    @Test
    public void testEmptyJournalIsReported()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create("MyProject", null, null, null); //$NON-NLS-1$

        String history =
            new WorkmateChatHistoryTool(store).execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        assertTrue(history.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(history.contains("\"turns\":[]")); //$NON-NLS-1$
        assertTrue(history.contains("\"hasSessionCursor\":false")); //$NON-NLS-1$
        assertTrue(history.contains("MyProject")); //$NON-NLS-1$
    }

    @Test
    public void testCompletedTurnsRenderOldestFirst()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        chat.completeTurn("q1", "a1", "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            true, 0);

        String history =
            new WorkmateChatHistoryTool(store).execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        assertTrue(history.contains("\"role\":\"user\"")); //$NON-NLS-1$
        assertTrue(history.contains("\"role\":\"assistant\"")); //$NON-NLS-1$
        assertTrue(history.indexOf("\"seq\":1") < history.indexOf("\"seq\":2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(history.contains("q1")); //$NON-NLS-1$
        assertTrue(history.contains("a1")); //$NON-NLS-1$
        assertTrue(history.contains("\"declaredFinal\":true")); //$NON-NLS-1$
        assertTrue(history.contains("\"hasSessionCursor\":true")); //$NON-NLS-1$
    }

    @Test
    public void testFailedTurnRendersAnErrorEntry()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        chat.failTurn("q1", "1C:Workmate failed to answer: sign-in required"); //$NON-NLS-1$ //$NON-NLS-2$

        String history =
            new WorkmateChatHistoryTool(store).execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        assertTrue(history.contains("\"role\":\"error\"")); //$NON-NLS-1$
        assertTrue(history.contains("sign-in required")); //$NON-NLS-1$
    }

    @Test
    public void testLimitKeepsTheNewestEntries()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        chat.completeTurn("q1", "a1", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        chat.completeTurn("q2", "a2", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = paramsOf("chatId", chat.chatId()); //$NON-NLS-1$
        params.put("limit", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        String history = new WorkmateChatHistoryTool(store).execute(params);

        assertFalse("limit=2 keeps only the newest pair", history.contains("q1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(history.contains("q2")); //$NON-NLS-1$
        assertTrue(history.contains("\"seq\":3")); //$NON-NLS-1$
        assertTrue(history.contains("\"seq\":4")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownChatIsActionable()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        String result =
            new WorkmateChatHistoryTool(store).execute(paramsOf("chatId", "nope")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("Unknown chatId")); //$NON-NLS-1$
    }

    @Test
    public void testNonPositiveLimitIsActionable()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        Map<String, String> params = paramsOf("chatId", chat.chatId()); //$NON-NLS-1$
        params.put("limit", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new WorkmateChatHistoryTool(store).execute(params);

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("limit must be a positive integer")); //$NON-NLS-1$
    }

    private static Map<String, String> paramsOf(String key, String value)
    {
        Map<String, String> params = new HashMap<>();
        params.put(key, value);
        return params;
    }
}
