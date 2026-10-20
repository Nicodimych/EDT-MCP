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

/** Tests for {@link WorkmateChatListTool}. */
public class WorkmateChatListToolTest
{
    @Test
    public void testListShowsCreatedChatsWithMetadata()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateChatStore.ChatRecord chat =
            store.create("MyProject", "custom", Integer.valueOf(3), "research"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String result = new WorkmateChatListTool(store).execute(null);

        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(result.contains(chat.chatId()));
        assertTrue(result.contains("MyProject")); //$NON-NLS-1$
        assertTrue(result.contains("research")); //$NON-NLS-1$
        assertTrue(result.contains("\"lastTurnStatus\":\"none\"")); //$NON-NLS-1$
        assertTrue(result.contains("\"hasUnread\":false")); //$NON-NLS-1$
        assertTrue(result.contains("persisted to workspace metadata")); //$NON-NLS-1$
    }

    @Test
    public void testListTracksLastTurnStatusAndUnreadFlag()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateChatStore.ChatRecord chat = store.create(null, null, null, "status-check"); //$NON-NLS-1$

        chat.setLastJobId("job-42"); //$NON-NLS-1$
        chat.beginSend();
        String whileRunning = new WorkmateChatListTool(store).execute(null);
        assertTrue(whileRunning.contains("\"lastTurnStatus\":\"running\"")); //$NON-NLS-1$
        assertTrue(whileRunning.contains("\"activeJobId\":\"job-42\"")); //$NON-NLS-1$
        assertTrue(whileRunning.contains("\"lastJobId\":\"job-42\"")); //$NON-NLS-1$

        chat.completeTurn("q1", "a1", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        chat.releaseSend();
        String afterDone = new WorkmateChatListTool(store).execute(null);
        assertTrue(afterDone.contains("\"lastTurnStatus\":\"done\"")); //$NON-NLS-1$
        assertTrue(afterDone.contains("\"hasUnread\":true")); //$NON-NLS-1$
        assertTrue(afterDone.contains("\"lastJobId\":\"job-42\"")); //$NON-NLS-1$
        assertFalse(afterDone.contains("\"activeJobId\"")); //$NON-NLS-1$

        Map<String, String> historyParams = new HashMap<>();
        historyParams.put("chatId", chat.chatId()); //$NON-NLS-1$
        new WorkmateChatHistoryTool(store).execute(historyParams);

        String afterRead = new WorkmateChatListTool(store).execute(null);
        assertTrue(afterRead.contains("\"hasUnread\":false")); //$NON-NLS-1$

        chat.failTurn("q2", "boom"); //$NON-NLS-1$ //$NON-NLS-2$
        String afterFail = new WorkmateChatListTool(store).execute(null);
        assertTrue(afterFail.contains("\"lastTurnStatus\":\"failed\"")); //$NON-NLS-1$
        assertTrue(afterFail.contains("\"hasUnread\":true")); //$NON-NLS-1$
    }

    @Test
    public void testListIsEmptyForAFreshStore()
    {
        String result = new WorkmateChatListTool(new WorkmateChatStore()).execute(null);

        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(result.contains("\"chats\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testLimitBoundsTheResult()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateChatStore.ChatRecord one = store.create(null, null, null, "one"); //$NON-NLS-1$
        WorkmateChatStore.ChatRecord two = store.create(null, null, null, "two"); //$NON-NLS-1$
        // Guarantee "two" is the most recently updated even when both creations share one
        // millisecond: touch it through a completed turn.
        try
        {
            Thread.sleep(5L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        two.completeTurn("q", "a", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("limit", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new WorkmateChatListTool(store).execute(params);

        assertTrue(result.contains(two.chatId()));
        assertFalse(result.contains(one.chatId()));
        assertFalse(result.contains("\"title\":\"one\"")); //$NON-NLS-1$
    }

    @Test
    public void testNonPositiveLimitIsActionable()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new WorkmateChatListTool(new WorkmateChatStore()).execute(params);

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("limit must be a positive integer")); //$NON-NLS-1$
    }
}
