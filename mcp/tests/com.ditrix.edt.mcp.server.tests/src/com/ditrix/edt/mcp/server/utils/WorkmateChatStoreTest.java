/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.ChatRecord;
import com.ditrix.edt.mcp.server.utils.WorkmateChatStore.TurnRecord;

/**
 * Tests for {@link WorkmateChatStore} - the local registry of stateful Workmate conversations.
 * The load-bearing contracts: the session cursor advances ONLY on a completed turn, a failed
 * send never moves it, and one send per chat is enforced.
 */
public class WorkmateChatStoreTest
{
    @Test
    public void testCreateGetListDrop()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create("MyProject", "custom", Integer.valueOf(3), "research"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertSame(chat, store.get(chat.chatId()));
        assertEquals("MyProject", chat.projectName()); //$NON-NLS-1$
        assertEquals("custom", chat.skillName()); //$NON-NLS-1$
        assertEquals(Integer.valueOf(3), chat.maxToolRounds());
        assertEquals("research", chat.title()); //$NON-NLS-1$
        assertNull("a fresh chat has no cloud cursor yet", chat.sessionState()); //$NON-NLS-1$

        assertEquals(1, store.list(0).size());
        assertTrue(store.drop(chat.chatId()));
        assertNull(store.get(chat.chatId()));
        assertFalse(store.drop(chat.chatId()));
        assertTrue(store.list(0).isEmpty());
    }

    @Test
    public void testUnknownChatIsNullNotException()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        assertNull(store.get("no-such-id")); //$NON-NLS-1$
        assertNull(store.get(null));
    }

    @Test
    public void testCompleteTurnAppendsJournalAndAdvancesCursor()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);

        chat.completeTurn("question one", "answer one", //$NON-NLS-1$ //$NON-NLS-2$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", true, 0); //$NON-NLS-1$

        List<TurnRecord> turns = chat.turns();
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role()); //$NON-NLS-1$
        assertEquals("question one", turns.get(0).text()); //$NON-NLS-1$
        assertEquals("assistant", turns.get(1).role()); //$NON-NLS-1$
        assertEquals("answer one", turns.get(1).text()); //$NON-NLS-1$
        assertTrue(turns.get(1).isDeclaredFinal());
        assertEquals("the completed turn returns the new cursor", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$
            chat.sessionState());
    }

    @Test
    public void testFailTurnKeepsTheOldCursor()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        chat.completeTurn("q", "a", "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            true, 0);

        chat.failTurn("q2", "1C:Workmate failed to answer: sign-in required"); //$NON-NLS-1$ //$NON-NLS-2$

        List<TurnRecord> turns = chat.turns();
        assertEquals(4, turns.size());
        assertEquals("user", turns.get(2).role()); //$NON-NLS-1$
        assertTrue(turns.get(3).isError());
        assertTrue(turns.get(3).text().contains("sign-in required")); //$NON-NLS-1$
        assertEquals("a failed send must NOT move the conversation cursor", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$
            chat.sessionState());
    }

    @Test
    public void testTurnWithoutNewCursorKeepsThePreviousOne()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        chat.completeTurn("q", "a", "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            true, 0);

        // Some Workmate builds return no handle at all: the journal still grows, the cursor
        // does not move backwards.
        chat.completeTurn("q2", "a2", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the cursor stays at the last confirmed point", //$NON-NLS-1$
            "{\"conversationId\":\"c1\",\"replyToMessageUuid\":\"u1\"}", //$NON-NLS-1$
            chat.sessionState());
        assertEquals(4, chat.turns().size());
    }

    @Test
    public void testBeginSendRefusesAConcurrentSend()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);

        assertTrue(chat.beginSend());
        assertFalse("a second concurrent send into the same chat must be refused", //$NON-NLS-1$
            chat.beginSend());
        assertTrue(chat.sendInFlight());

        chat.releaseSend();
        assertFalse(chat.sendInFlight());
        assertTrue("after the release the next send may proceed", chat.beginSend()); //$NON-NLS-1$
        chat.releaseSend();
    }

    @Test
    public void testJournalIsCapped()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord chat = store.create(null, null, null, null);
        for (int i = 0; i < WorkmateChatStore.MAX_TURNS_PER_CHAT / 2 + 10; i++)
        {
            chat.completeTurn("q" + i, "a" + i, null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertEquals(WorkmateChatStore.MAX_TURNS_PER_CHAT, chat.turns().size());
    }

    @Test
    public void testOldestChatIsEvictedPastTheCap()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord first = store.create(null, null, null, null);
        for (int i = 0; i < WorkmateChatStore.MAX_CHATS; i++)
        {
            store.create(null, null, null, null);
        }
        assertNull("the oldest chat is evicted once the cap is exceeded", //$NON-NLS-1$
            store.get(first.chatId()));
        assertEquals(WorkmateChatStore.MAX_CHATS, store.list(0).size());
    }

    @Test
    public void testListOrdersByMostRecentlyUpdated()
    {
        WorkmateChatStore store = new WorkmateChatStore();
        ChatRecord older = store.create(null, null, null, "older"); //$NON-NLS-1$
        ChatRecord newer = store.create(null, null, null, "newer"); //$NON-NLS-1$

        // Touch the older one second: its update time moves past the newer chat's creation.
        try
        {
            Thread.sleep(5L);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        older.completeTurn("q", "a", null, true, 0); //$NON-NLS-1$ //$NON-NLS-2$

        List<ChatRecord> list = store.list(0);
        assertEquals(2, list.size());
        assertSame(older, list.get(0));
        assertSame(newer, list.get(1));
    }

    @Test
    public void testSharedInstanceIsStable()
    {
        assertSame(WorkmateChatStore.shared(), WorkmateChatStore.shared());
        assertNotNull(WorkmateChatStore.shared());
    }

    @Test
    public void testPersistenceAcrossStoreReload() throws Exception
    {
        java.nio.file.Path tmpFile = java.nio.file.Files.createTempFile("workmate-chats-", ".json"); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            WorkmateChatStore firstStore = new WorkmateChatStore(tmpFile);
            ChatRecord created = firstStore.create("MyProject", "custom", Integer.valueOf(80), "Wave 8"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            created.setLastJobId("job-42"); //$NON-NLS-1$
            created.completeTurn("first question", "first answer", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"conversationId\":\"c-88\",\"replyToMessageUuid\":\"u-99\"}", true, 1); //$NON-NLS-1$

            WorkmateChatStore reloadedStore = new WorkmateChatStore(tmpFile);
            ChatRecord restored = reloadedStore.get(created.chatId());
            assertNotNull(restored);
            assertEquals("MyProject", restored.projectName()); //$NON-NLS-1$
            assertEquals("custom", restored.skillName()); //$NON-NLS-1$
            assertEquals(Integer.valueOf(80), restored.maxToolRounds());
            assertEquals("Wave 8", restored.title()); //$NON-NLS-1$
            assertEquals("job-42", restored.lastJobId()); //$NON-NLS-1$
            assertEquals("{\"conversationId\":\"c-88\",\"replyToMessageUuid\":\"u-99\"}", //$NON-NLS-1$
                restored.sessionState());
            assertTrue(restored.hasUnread());
            assertEquals("done", restored.lastTurnStatus()); //$NON-NLS-1$
            assertEquals(2, restored.turns().size());
            assertEquals("first answer", restored.turns().get(1).text()); //$NON-NLS-1$
        }
        finally
        {
            java.nio.file.Files.deleteIfExists(tmpFile);
        }
    }
}
