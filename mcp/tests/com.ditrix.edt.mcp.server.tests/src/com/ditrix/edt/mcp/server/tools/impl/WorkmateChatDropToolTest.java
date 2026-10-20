/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;

/** Tests for {@link WorkmateChatDropTool}. */
public class WorkmateChatDropToolTest
{
    @Test
    public void testDropForgetsTheChatAndSaysTheCloudStays()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateChatStore.ChatRecord chat = store.create(null, null, null, null);

        String result = new WorkmateChatDropTool(store)
            .execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(result.contains(chat.chatId()));
        assertTrue("the contract says the cloud conversation still exists", //$NON-NLS-1$
            result.contains("cloud conversation still exists")); //$NON-NLS-1$
        assertNull(store.get(chat.chatId()));
    }

    @Test
    public void testDroppingTwiceIsAnActionableError()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        WorkmateChatStore.ChatRecord chat = store.create(null, null, null, null);
        WorkmateChatDropTool tool = new WorkmateChatDropTool(store);
        tool.execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        String again = tool.execute(paramsOf("chatId", chat.chatId())); //$NON-NLS-1$

        assertTrue(again.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(again.contains("Unknown chatId")); //$NON-NLS-1$
    }

    @Test
    public void testBlankChatIdIsActionable()
    {
        String result =
            new WorkmateChatDropTool(new WorkmateChatStore()).execute(paramsOf("chatId", " ")); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("chatId must contain a non-empty chat id")); //$NON-NLS-1$
    }

    private static Map<String, String> paramsOf(String key, String value)
    {
        Map<String, String> params = new HashMap<>();
        params.put(key, value);
        return params;
    }
}
