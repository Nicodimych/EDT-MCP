/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateChatStore;

/** Tests for {@link WorkmateChatCreateTool}. */
public class WorkmateChatCreateToolTest
{
    @Test
    public void testCreateReturnsUsableChatId()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        String result = new WorkmateChatCreateTool(store).execute(null);

        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
        String chatId = extractJsonString(result, "chatId"); //$NON-NLS-1$
        assertNotNull(chatId);
        assertNotNull(store.get(chatId));
        org.junit.Assert.assertEquals(
            Integer.valueOf(WorkmateChatCreateTool.DEFAULT_MAX_TOOL_ROUNDS),
            store.get(chatId).maxToolRounds());
        assertTrue(result.contains("\"maxToolRounds\":80")); //$NON-NLS-1$
        assertTrue(result.contains("No cloud conversation exists yet")); //$NON-NLS-1$
    }

    @Test
    public void testCreateKeepsBoundSettings()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("skillName", "custom"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("maxToolRounds", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("title", "research"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new WorkmateChatCreateTool(store).execute(params);

        String chatId = extractJsonString(result, "chatId"); //$NON-NLS-1$
        WorkmateChatStore.ChatRecord chat = store.get(chatId);
        org.junit.Assert.assertEquals("MyProject", chat.projectName()); //$NON-NLS-1$
        org.junit.Assert.assertEquals("custom", chat.skillName()); //$NON-NLS-1$
        org.junit.Assert.assertEquals(Integer.valueOf(3), chat.maxToolRounds());
        org.junit.Assert.assertEquals("research", chat.title()); //$NON-NLS-1$
        assertNull("a fresh chat has no cloud cursor yet", chat.sessionState()); //$NON-NLS-1$
    }

    @Test
    public void testCreateRejectsNonPositiveMaxToolRounds()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        Map<String, String> params = new HashMap<>();
        params.put("maxToolRounds", "0"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new WorkmateChatCreateTool(store).execute(params);

        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("maxToolRounds must be a positive integer")); //$NON-NLS-1$
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
}
