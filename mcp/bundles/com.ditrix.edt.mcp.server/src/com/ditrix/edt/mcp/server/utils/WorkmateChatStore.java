/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Registry of stateful 1C:Workmate conversations driven through {@code workmate_chat_*} tools.
 * <p>
 * A chat is a local handle over a cloud conversation: it remembers the project and skill the
 * caller chose, the serialized {@code ConversationSession} cursor that lands the next message
 * in the SAME cloud conversation, and the journal of completed turns. The cursor is advanced
 * ONLY after a turn completed and returned a new one - a failed or timed-out send keeps the
 * old cursor, so the client never resumes from a point the cloud never confirmed.
 * <p>
 * When created via {@link #shared()}, the store also mirrors its state to disk inside the
 * workspace metadata directory ({@code .metadata/.plugins/com.ditrix.edt.mcp.server/workmate-chats.json})
 * so chats, journals, and cloud conversation cursors survive EDT restarts.
 */
public final class WorkmateChatStore
{
    /** Hard cap on remembered chats; the oldest untouched chat is evicted past it. */
    static final int MAX_CHATS = 50;

    /** Hard cap on the journal of one chat; the oldest turns are evicted past it. */
    static final int MAX_TURNS_PER_CHAT = 200;

    private static final Object SHARED_LOCK = new Object();
    private static WorkmateChatStore shared;

    /** chatId -> chat. Insertion-ordered so eviction drops the OLDEST chat. */
    private final Map<String, ChatRecord> chats = new LinkedHashMap<>();

    /** Optional backing file for persisting chats across EDT restarts; {@code null} in plain unit tests. */
    private final Path storageFile;

    public WorkmateChatStore()
    {
        this(null);
    }

    public WorkmateChatStore(Path storageFile)
    {
        this.storageFile = storageFile;
        if (storageFile != null)
        {
            loadFromDisk();
        }
    }

    public static WorkmateChatStore shared()
    {
        synchronized (SHARED_LOCK)
        {
            if (shared == null)
            {
                shared = new WorkmateChatStore(defaultStoragePath());
            }
            return shared;
        }
    }

    private static Path defaultStoragePath()
    {
        try
        {
            if (ResourcesPlugin.getWorkspace() != null
                && ResourcesPlugin.getWorkspace().getRoot() != null)
            {
                IPath rootLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
                if (rootLocation != null)
                {
                    return rootLocation.append(".metadata") //$NON-NLS-1$
                        .append(".plugins") //$NON-NLS-1$
                        .append("com.ditrix.edt.mcp.server") //$NON-NLS-1$
                        .append("workmate-chats.json") //$NON-NLS-1$
                        .toFile()
                        .toPath();
                }
            }
        }
        catch (Throwable ignored) // NOSONAR headless / non-OSGi environment fallback
        {
            // Fall through to user.home fallback
        }
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (home != null && !home.isEmpty())
        {
            return Paths.get(home, ".edt-mcp", "workmate-chats.json"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Creates a local chat record. No cloud conversation exists yet: the first
     * {@code workmate_chat_send} starts one.
     *
     * @param projectName optional EDT project name the chat runs in (may be {@code null})
     * @param skillName optional Workmate skill (may be {@code null} to use the gateway default)
     * @param maxToolRounds optional Workmate tool-round limit (may be {@code null})
     * @param title optional human-readable label
     * @return the new record, already registered
     */
    public ChatRecord create(String projectName, String skillName, Integer maxToolRounds,
        String title)
    {
        ChatRecord record = new ChatRecord(this, UUID.randomUUID().toString(), projectName,
            skillName, maxToolRounds, title, System.currentTimeMillis(),
            System.currentTimeMillis());
        synchronized (chats)
        {
            while (chats.size() >= MAX_CHATS)
            {
                String oldest = chats.keySet().iterator().next();
                chats.remove(oldest);
            }
            chats.put(record.chatId(), record);
            saveToDiskLocked();
        }
        return record;
    }

    /**
     * Returns the chat, or {@code null} when the id is unknown.
     *
     * @param chatId the id a {@code workmate_chat_*} tool returned
     * @return the record, or {@code null}
     */
    public ChatRecord get(String chatId)
    {
        synchronized (chats)
        {
            return chatId == null ? null : chats.get(chatId);
        }
    }

    /**
     * Lists the known chats, most recently updated first.
     *
     * @param limit maximum number of entries (a non-positive value lists all)
     * @return the chat records
     */
    public List<ChatRecord> list(int limit)
    {
        List<ChatRecord> all;
        synchronized (chats)
        {
            all = new ArrayList<>(chats.values());
        }
        all.sort((a, b) -> Long.compare(b.updatedAtMillis(), a.updatedAtMillis()));
        if (limit > 0 && all.size() > limit)
        {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /**
     * Forgets a chat and its journal. The CLOUD conversation is NOT deleted - Workmate keeps
     * it and it stays visible in the EDT chat panel; only this plugin stops remembering how
     * to continue it.
     *
     * @param chatId the chat to forget
     * @return whether a chat was actually removed
     */
    public boolean drop(String chatId)
    {
        synchronized (chats)
        {
            boolean removed = chats.remove(chatId) != null;
            if (removed)
            {
                saveToDiskLocked();
            }
            return removed;
        }
    }

    void persistChange()
    {
        if (storageFile == null)
        {
            return;
        }
        synchronized (chats)
        {
            saveToDiskLocked();
        }
    }

    private void saveToDiskLocked()
    {
        if (storageFile == null)
        {
            return;
        }
        try
        {
            Path parent = storageFile.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (ChatRecord record : chats.values())
            {
                arr.add(record.toJson());
            }
            root.add("chats", arr); //$NON-NLS-1$
            Path tmp = storageFile.resolveSibling(storageFile.getFileName().toString() + ".tmp"); //$NON-NLS-1$
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, storageFile, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception ignored) // NOSONAR persistence is best-effort and must never break tool execution
        {
            // Ignore I/O failures
        }
    }

    private void loadFromDisk()
    {
        if (storageFile == null || !Files.isRegularFile(storageFile))
        {
            return;
        }
        try
        {
            String content = Files.readString(storageFile, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject())
            {
                return;
            }
            JsonArray arr = parsed.getAsJsonObject().getAsJsonArray("chats"); //$NON-NLS-1$
            if (arr == null)
            {
                return;
            }
            synchronized (chats)
            {
                for (JsonElement el : arr)
                {
                    if (!el.isJsonObject())
                    {
                        continue;
                    }
                    ChatRecord record = ChatRecord.fromJson(this, el.getAsJsonObject());
                    if (record != null && record.chatId() != null)
                    {
                        chats.put(record.chatId(), record);
                    }
                }
            }
        }
        catch (Exception ignored) // NOSONAR corrupt state file is ignored cleanly
        {
            // Ignore corrupt state file
        }
    }

    static String optString(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    static Integer optInt(JsonObject obj, String key)
    {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? Integer.valueOf(el.getAsInt()) : null;
    }

    static long optLong(JsonObject obj, String key, long fallback)
    {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsLong() : fallback;
    }

    static boolean optBoolean(JsonObject obj, String key, boolean fallback)
    {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsBoolean() : fallback;
    }

    /** One stateful Workmate conversation as this plugin remembers it. */
    public static final class ChatRecord
    {
        private final WorkmateChatStore owner;
        private final String chatId;
        private final String projectName;
        private final String skillName;
        private final Integer maxToolRounds;
        private final String title;
        private final long createdAtMillis;
        private final List<TurnRecord> turns = Collections.synchronizedList(new ArrayList<>());

        /** Cursor from the last COMPLETED turn; {@code null} until the first send finishes. */
        private volatile String sessionState;

        /** Guards against two concurrent sends into the same conversation. */
        private volatile boolean sendInFlight;

        /** True when a turn completed or failed since the last workmate_chat_history read. */
        private volatile boolean hasUnread;

        /** Job ID of the latest (or currently running) workmate_chat_send call. */
        private volatile String lastJobId;

        private long updatedAtMillis;

        ChatRecord(String chatId, String projectName, String skillName, Integer maxToolRounds,
            String title)
        {
            this(null, chatId, projectName, skillName, maxToolRounds, title,
                System.currentTimeMillis(), System.currentTimeMillis());
        }

        ChatRecord(WorkmateChatStore owner, String chatId, String projectName, String skillName,
            Integer maxToolRounds, String title, long createdAtMillis, long updatedAtMillis)
        {
            this.owner = owner;
            this.chatId = chatId;
            this.projectName = projectName;
            this.skillName = skillName;
            this.maxToolRounds = maxToolRounds;
            this.title = title;
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
        }

        public String chatId()
        {
            return chatId;
        }

        public String projectName()
        {
            return projectName;
        }

        public String skillName()
        {
            return skillName;
        }

        public Integer maxToolRounds()
        {
            return maxToolRounds;
        }

        public String title()
        {
            return title;
        }

        public long createdAtMillis()
        {
            return createdAtMillis;
        }

        public long updatedAtMillis()
        {
            return updatedAtMillis;
        }

        public String sessionState()
        {
            return sessionState;
        }

        public boolean sendInFlight()
        {
            return sendInFlight;
        }

        public boolean hasUnread()
        {
            return hasUnread;
        }

        public String lastJobId()
        {
            return lastJobId;
        }

        public String activeJobId()
        {
            return sendInFlight ? lastJobId : null;
        }

        public void setLastJobId(String jobId)
        {
            this.lastJobId = jobId;
            if (owner != null)
            {
                owner.persistChange();
            }
        }

        public void markRead()
        {
            if (hasUnread)
            {
                hasUnread = false;
                if (owner != null)
                {
                    owner.persistChange();
                }
            }
        }

        /**
         * Summary status of the latest exchange for {@code workmate_chat_list}: {@code "running"}
         * while a send is in flight, {@code "none"} before any send finishes, {@code "done"} when
         * the latest turn produced an answer, or {@code "failed"} when the latest turn errored.
         *
         * @return one of {@code "none"}, {@code "running"}, {@code "done"}, {@code "failed"}
         */
        public String lastTurnStatus()
        {
            if (sendInFlight)
            {
                return "running"; //$NON-NLS-1$
            }
            synchronized (turns)
            {
                if (turns.isEmpty())
                {
                    return "none"; //$NON-NLS-1$
                }
                return turns.get(turns.size() - 1).isError() ? "failed" : "done"; //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /**
         * Claims the right to send one message, refusing a second concurrent send into the
         * same conversation: two in-flight sends would race on the same cursor and one of
         * them would branch the conversation.
         *
         * @return {@code true} when the claim was taken (the caller MUST pair it with
         *         {@link #releaseSend()})
         */
        public boolean beginSend()
        {
            synchronized (this)
            {
                if (sendInFlight)
                {
                    return false;
                }
                sendInFlight = true;
                return true;
            }
        }

        /** Releases the send claim taken by {@link #beginSend()}. */
        public void releaseSend()
        {
            sendInFlight = false;
        }

        /**
         * Records one completed send: the user message, Workmate's answer, and - when the
         * platform returned one - the new conversation cursor. The cursor is advanced only
         * here, only after a turn that actually completed: a failed send never moves it.
         *
         * @param userMessage the message that was sent
         * @param answer Workmate's accepted answer text
         * @param newSessionState the cursor to continue from, or {@code null} when the
         *        platform exposed none
         * @param declaredFinal whether Workmate marked the answer as final
         * @param continuations how many continuation nudges the turn loop needed
         */
        public void completeTurn(String userMessage, String answer, String newSessionState,
            boolean declaredFinal, int continuations)
        {
            synchronized (turns)
            {
                appendLocked(new TurnRecord("user", userMessage, false, false, 0)); //$NON-NLS-1$
                appendLocked(new TurnRecord("assistant", answer, false, declaredFinal, //$NON-NLS-1$
                    continuations));
            }
            if (newSessionState != null)
            {
                sessionState = newSessionState;
            }
            hasUnread = true;
            touch();
            if (owner != null)
            {
                owner.persistChange();
            }
        }

        /**
         * Records a send that failed before producing an answer. The cursor is NOT advanced:
         * the conversation may have moved in the cloud, but this plugin has no confirmed
         * point to continue from, so the old cursor stays and the journal says why.
         *
         * @param userMessage the message that was attempted
         * @param error the actionable failure description
         */
        public void failTurn(String userMessage, String error)
        {
            synchronized (turns)
            {
                appendLocked(new TurnRecord("user", userMessage, false, false, 0)); //$NON-NLS-1$
                appendLocked(new TurnRecord("error", error, true, false, 0)); //$NON-NLS-1$
            }
            hasUnread = true;
            touch();
            if (owner != null)
            {
                owner.persistChange();
            }
        }

        private void appendLocked(TurnRecord turn)
        {
            turns.add(turn);
            while (turns.size() > MAX_TURNS_PER_CHAT)
            {
                turns.remove(0);
            }
        }

        private void touch()
        {
            updatedAtMillis = System.currentTimeMillis();
        }

        /**
         * A copy of the journal, oldest first.
         *
         * @return the recorded turns
         */
        public List<TurnRecord> turns()
        {
            synchronized (turns)
            {
                return new ArrayList<>(turns);
            }
        }

        JsonObject toJson()
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("chatId", chatId); //$NON-NLS-1$
            if (projectName != null)
            {
                obj.addProperty("projectName", projectName); //$NON-NLS-1$
            }
            if (skillName != null)
            {
                obj.addProperty("skillName", skillName); //$NON-NLS-1$
            }
            if (maxToolRounds != null)
            {
                obj.addProperty("maxToolRounds", maxToolRounds); //$NON-NLS-1$
            }
            if (title != null)
            {
                obj.addProperty("title", title); //$NON-NLS-1$
            }
            obj.addProperty("createdAtMillis", Long.valueOf(createdAtMillis)); //$NON-NLS-1$
            obj.addProperty("updatedAtMillis", Long.valueOf(updatedAtMillis)); //$NON-NLS-1$
            if (sessionState != null)
            {
                obj.addProperty("sessionState", sessionState); //$NON-NLS-1$
            }
            obj.addProperty("hasUnread", Boolean.valueOf(hasUnread)); //$NON-NLS-1$
            if (lastJobId != null)
            {
                obj.addProperty("lastJobId", lastJobId); //$NON-NLS-1$
            }
            JsonArray turnsArr = new JsonArray();
            synchronized (turns)
            {
                for (TurnRecord t : turns)
                {
                    JsonObject tObj = new JsonObject();
                    tObj.addProperty("role", t.role()); //$NON-NLS-1$
                    tObj.addProperty("text", t.text()); //$NON-NLS-1$
                    tObj.addProperty("error", Boolean.valueOf(t.isError())); //$NON-NLS-1$
                    tObj.addProperty("declaredFinal", Boolean.valueOf(t.isDeclaredFinal())); //$NON-NLS-1$
                    tObj.addProperty("continuations", Integer.valueOf(t.continuations())); //$NON-NLS-1$
                    tObj.addProperty("atMillis", Long.valueOf(t.atMillis())); //$NON-NLS-1$
                    turnsArr.add(tObj);
                }
            }
            obj.add("turns", turnsArr); //$NON-NLS-1$
            return obj;
        }

        static ChatRecord fromJson(WorkmateChatStore owner, JsonObject obj)
        {
            String id = optString(obj, "chatId"); //$NON-NLS-1$
            if (id == null)
            {
                return null;
            }
            long created = optLong(obj, "createdAtMillis", System.currentTimeMillis()); //$NON-NLS-1$
            long updated = optLong(obj, "updatedAtMillis", created); //$NON-NLS-1$
            ChatRecord record = new ChatRecord(owner, id,
                optString(obj, "projectName"), //$NON-NLS-1$
                optString(obj, "skillName"), //$NON-NLS-1$
                optInt(obj, "maxToolRounds"), //$NON-NLS-1$
                optString(obj, "title"), //$NON-NLS-1$
                created, updated);
            record.sessionState = optString(obj, "sessionState"); //$NON-NLS-1$
            record.hasUnread = optBoolean(obj, "hasUnread", false); //$NON-NLS-1$
            record.lastJobId = optString(obj, "lastJobId"); //$NON-NLS-1$
            JsonArray turnsArr = obj.getAsJsonArray("turns"); //$NON-NLS-1$
            if (turnsArr != null)
            {
                for (JsonElement el : turnsArr)
                {
                    if (!el.isJsonObject())
                    {
                        continue;
                    }
                    JsonObject tObj = el.getAsJsonObject();
                    String role = optString(tObj, "role"); //$NON-NLS-1$
                    String text = optString(tObj, "text"); //$NON-NLS-1$
                    if (role != null && text != null)
                    {
                        record.appendLocked(new TurnRecord(role, text,
                            optBoolean(tObj, "error", false), //$NON-NLS-1$
                            optBoolean(tObj, "declaredFinal", false), //$NON-NLS-1$
                            optInt(tObj, "continuations") != null //$NON-NLS-1$
                                ? optInt(tObj, "continuations").intValue() : 0, //$NON-NLS-1$
                            optLong(tObj, "atMillis", created))); //$NON-NLS-1$
                    }
                }
            }
            return record;
        }
    }

    /** One journal entry: a user message, an assistant answer, or an error. */
    public static final class TurnRecord
    {
        private final String role;
        private final String text;
        private final boolean error;
        private final boolean declaredFinal;
        private final int continuations;
        private final long atMillis;

        TurnRecord(String role, String text, boolean error, boolean declaredFinal,
            int continuations)
        {
            this(role, text, error, declaredFinal, continuations, System.currentTimeMillis());
        }

        TurnRecord(String role, String text, boolean error, boolean declaredFinal,
            int continuations, long atMillis)
        {
            this.role = role;
            this.text = text;
            this.error = error;
            this.declaredFinal = declaredFinal;
            this.continuations = continuations;
            this.atMillis = atMillis;
        }

        public String role()
        {
            return role;
        }

        public String text()
        {
            return text;
        }

        public boolean isError()
        {
            return error;
        }

        public boolean isDeclaredFinal()
        {
            return declaredFinal;
        }

        public int continuations()
        {
            return continuations;
        }

        public long atMillis()
        {
            return atMillis;
        }
    }
}
