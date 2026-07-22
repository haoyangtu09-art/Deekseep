package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges DeepSeek's online history objects to the local editor.
 *
 * DeepSeek 2.2.2 may render a pw0 response in memory without materialising the
 * per-session SQLite table.  The editor therefore keeps a small, process-local
 * snapshot of the exact persistence rows produced by uo.O().  It can display
 * those rows immediately and only materialises them if the user actually saves
 * an edit.
 */
final class HistoryBridge {
    private static final int MAX_SNAPSHOTS = 96;
    private static final Map<String, Snapshot> SNAPSHOTS =
            new LinkedHashMap<String, Snapshot>(MAX_SNAPSHOTS + 1, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
                    return size() > MAX_SNAPSHOTS;
                }
            };

    static final class Row {
        final int messageId;
        final Integer parentId;
        final String role;
        final Boolean thinkingEnabled;
        final String status;
        final double insertedAt;
        final String feedbackType;
        final int accumulatedTokenUsage;
        final boolean banEdit;
        final boolean banRegenerate;
        final String tips;
        final String fragments;
        final String conversationMode;

        Row(int messageId, Integer parentId, String role, Boolean thinkingEnabled,
            String status, double insertedAt, String feedbackType,
            int accumulatedTokenUsage, boolean banEdit, boolean banRegenerate,
            String tips, String fragments, String conversationMode) {
            this.messageId = messageId;
            this.parentId = parentId;
            this.role = role;
            this.thinkingEnabled = thinkingEnabled;
            this.status = status;
            this.insertedAt = insertedAt;
            this.feedbackType = feedbackType;
            this.accumulatedTokenUsage = accumulatedTokenUsage;
            this.banEdit = banEdit;
            this.banRegenerate = banRegenerate;
            this.tips = tips;
            this.fragments = fragments;
            this.conversationMode = conversationMode;
        }
    }

    static final class Snapshot {
        final String sid;
        final Integer version;
        final Integer currentMessageId;
        final boolean complete;
        final List<Row> rows;

        Snapshot(String sid, Integer version, Integer currentMessageId, boolean complete,
                 List<Row> rows) {
            this.sid = sid;
            this.version = version;
            this.currentMessageId = currentMessageId;
            this.complete = complete;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        }
    }

    static final class Result {
        final int cleaned;
        final int rows;

        Result(int cleaned, int rows) {
            this.cleaned = cleaned;
            this.rows = rows;
        }
    }

    private HistoryBridge() {}

    /** Removes every exact leading legacy Deekseep system wrapper, not just one. */
    static String stripInjectedSystemPrompts(String text) {
        if (text == null) return "";
        String out = text;
        while (true) {
            int after = injectedPrefixEnd(out);
            if (after < 0) return out;
            out = out.substring(after);
        }
    }

    static String wrapSystemPrompt(String systemPrompt, String userText) {
        String prompt = systemPrompt == null ? "" : systemPrompt;
        String user = stripInjectedSystemPrompts(userText == null ? "" : userText);
        return "<system>\n" + prompt + "\n</system>\n\n" + user;
    }

    private static int injectedPrefixEnd(String text) {
        // The injector always starts at byte zero and owns the two-newline separator.  Being
        // deliberately strict here preserves user-authored indented text and XML examples.
        if (text.startsWith("<system>\n")) {
            String close = "\n</system>\n\n";
            int at = text.indexOf(close, "<system>\n".length());
            return at < 0 ? -1 : at + close.length();
        }
        // Accept the equivalent legacy CRLF representation imported by older builds/tools.
        if (text.startsWith("<system>\r\n")) {
            String close = "\r\n</system>\r\n\r\n";
            int at = text.indexOf(close, "<system>\r\n".length());
            return at < 0 ? -1 : at + close.length();
        }
        return -1;
    }

    /** Sanitises a pw0 object synchronously, then captures exact uo.O() rows. */
    static Result processHistoryResponse(Object response) {
        if (response == null) return new Result(0, 0);
        Object session = field(response, "a");
        String sid = asString(field(session, "a"));
        Object messagesValue = field(response, "b");
        if (sid == null || sid.length() == 0 || !(messagesValue instanceof List)) {
            return new Result(0, 0);
        }

        List<?> messages = (List<?>) messagesValue;
        int cleaned = 0;
        ArrayList<Row> rows = new ArrayList<>();
        for (Object message : messages) {
            if (message == null) continue;
            cleaned += sanitizeMessageFragments(message);
            Row row = persistenceRow(message);
            if (row != null) rows.add(row);
        }
        captureSnapshot(sid, asInteger(field(session, "c")),
                asInteger(field(session, "d")), asString(field(response, "c")), rows);
        return new Result(cleaned, rows.size());
    }

    private static void captureSnapshot(String sid, Integer version, Integer currentMessageId,
                                        String cacheControl, List<Row> incoming) {
        synchronized (SNAPSHOTS) {
            Snapshot old = SNAPSHOTS.get(sid);
            if (old != null && old.version != null && version != null
                    && version.intValue() < old.version.intValue()) return;
            if (old != null && old.version != null && version == null) return;

            List<Row> rows = incoming;
            boolean merge = "MERGE".equals(cacheControl);
            boolean complete = !merge;
            if (old != null && merge) {
                LinkedHashMap<Integer, Row> merged = new LinkedHashMap<>();
                for (Row row : old.rows) merged.put(row.messageId, row);
                for (Row row : incoming) merged.put(row.messageId, row);
                rows = new ArrayList<>(merged.values());
                complete = old.complete;
            }
            Integer keptVersion = version != null ? version : old == null ? null : old.version;
            Integer keptCurrent = merge && currentMessageId == null && old != null
                    ? old.currentMessageId : currentMessageId;
            SNAPSHOTS.put(sid, new Snapshot(sid, keptVersion, keptCurrent, complete, rows));
        }
    }

    static Object snapshotLock() { return SNAPSHOTS; }

    static boolean isCurrentSnapshot(Snapshot snapshot) {
        synchronized (SNAPSHOTS) {
            return snapshot != null && SNAPSHOTS.get(snapshot.sid) == snapshot;
        }
    }

    static Snapshot snapshot(String sid) {
        if (sid == null) return null;
        synchronized (SNAPSHOTS) { return SNAPSHOTS.get(sid); }
    }

    /** Drops stale in-memory history immediately after an explicit user deletion. */
    static void forgetSession(String sid) {
        if (sid == null) return;
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.remove(sid);
        }
    }

    /** Captures live tp.f maps so a brand-new conversation is visible to the editor. */
    static Result processNativeSessions(Object sessionsValue) {
        if (!(sessionsValue instanceof List)) return new Result(0, 0);
        int cleaned = 0;
        int rows = 0;
        for (Object session : (List<?>) sessionsValue) {
            Result result = processNativeSessionObject(session, null);
            cleaned += result.cleaned;
            rows += result.rows;
        }
        return new Result(cleaned, rows);
    }

    /**
     * Captures only the requested live tp session. The first argument may be the captured
     * sidebar List<tp> or an already selected tp object; unrelated message maps are untouched.
     */
    static Result processNativeSession(Object sessionsValue, String targetSid) {
        if (targetSid == null || targetSid.length() == 0) return new Result(0, 0);
        if (sessionsValue instanceof List) {
            for (Object session : (List<?>) sessionsValue) {
                if (!targetSid.equals(asString(field(session, "a")))) continue;
                return processNativeSessionObject(session, targetSid);
            }
            return new Result(0, 0);
        }
        return processNativeSessionObject(sessionsValue, targetSid);
    }

    private static Result processNativeSessionObject(Object session, String expectedSid) {
        if (session == null) return new Result(0, 0);
        String sid = asString(field(session, "a"));
        Object mapValue = field(session, "f");
        if (sid == null || sid.length() == 0
                || (expectedSid != null && !expectedSid.equals(sid))
                || !(mapValue instanceof Map)) return new Result(0, 0);

        int cleaned = 0;
        ArrayList<Row> liveRows = new ArrayList<>();
        try {
            for (Object message : new ArrayList<Object>(((Map<?, ?>) mapValue).values())) {
                if (message == null) continue;
                cleaned += sanitizeMessageFragments(message);
                Row row = persistenceRow(message);
                if (row != null && row.messageId != Integer.MIN_VALUE) liveRows.add(row);
            }
        } catch (Throwable ignored) {
            return new Result(cleaned, 0);
        }
        if (liveRows.isEmpty()) return new Result(cleaned, 0);

        Integer current = callInteger(session, "t");
        if (!containsMessage(liveRows, current)) current = callInteger(session, "e");
        if (!containsMessage(liveRows, current)) current = null;
        captureNativeSnapshot(sid, asInteger(field(session, "n")), current, liveRows);
        return new Result(cleaned, liveRows.size());
    }

    private static void captureNativeSnapshot(String sid, Integer version, Integer current,
                                              List<Row> rows) {
        synchronized (SNAPSHOTS) {
            Snapshot old = SNAPSHOTS.get(sid);
            if (old != null && old.version != null) {
                if (version == null || version.intValue() < old.version.intValue()) return;
                if (old.complete && version.intValue() == old.version.intValue()
                        && sameRows(old.rows, rows)
                        && (current == null || current.equals(old.currentMessageId))) return;
            }
            // tp is mutable and can contain an in-flight turn, so expose it read-only. A later
            // versioned pw0 REPLACE promotes the same session to a materialisable snapshot.
            SNAPSHOTS.put(sid, new Snapshot(sid, version, current, false, rows));
        }
    }

    private static boolean containsMessage(List<Row> rows, Integer messageId) {
        if (messageId == null) return false;
        for (Row row : rows) if (row.messageId == messageId.intValue()) return true;
        return false;
    }

    private static boolean sameRows(List<Row> left, List<Row> right) {
        if (left.size() != right.size()) return false;
        LinkedHashMap<Integer, Row> byId = new LinkedHashMap<>();
        for (Row row : left) byId.put(row.messageId, row);
        for (Row row : right) {
            Row old = byId.get(row.messageId);
            if (old == null || !sameValue(old.parentId, row.parentId)
                    || !sameValue(old.role, row.role)
                    || !sameValue(old.status, row.status)
                    || !sameValue(old.fragments, row.fragments)) return false;
        }
        return true;
    }

    private static boolean sameValue(Object left, Object right) {
        return left == right || (left != null && left.equals(right));
    }

    private static Integer callInteger(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return asInteger(method.invoke(target));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Defence in depth for gm8/fm8.b rows immediately before the host writes them. */
    static int sanitizeRepositoryRows(Object[] args) {
        if (args == null || args.length < 5 || !(args[4] instanceof List)) return 0;
        int cleaned = 0;
        for (Object row : (List<?>) args[4]) {
            String json = asString(field(row, "l"));
            if (json == null) continue;
            String safe = sanitizeFragmentsJson(json);
            if (!safe.equals(json) && setField(row, "l", safe)) cleaned++;
        }
        return cleaned;
    }

    static String sanitizeFragmentsJson(String json) {
        if (json == null || json.length() == 0) return json;
        try {
            JSONArray fragments = new JSONArray(json);
            boolean changed = false;
            for (int i = 0; i < fragments.length(); i++) {
                JSONObject fragment = fragments.optJSONObject(i);
                if (fragment == null || !"REQUEST".equals(fragment.optString("type"))) continue;
                String content = fragment.optString("content", "");
                String safe = stripInjectedSystemPrompts(content);
                if (!safe.equals(content)) {
                    fragment.put("content", safe);
                    changed = true;
                }
            }
            return changed ? fragments.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    private static int sanitizeMessageFragments(Object message) {
        Object value = field(message, "t");
        if (!(value instanceof List)) return 0;
        List fragments = (List) value;
        ArrayList<Object> replacementList = null;
        int cleaned = 0;
        for (int i = 0; i < fragments.size(); i++) {
            Object fragment = fragments.get(i);
            if (!"REQUEST".equals(asString(field(fragment, "a")))) continue;
            String content = asString(field(fragment, "c"));
            if (content == null) continue;
            String safe = stripInjectedSystemPrompts(content);
            if (safe.equals(content)) continue;
            if (setField(fragment, "c", safe)) {
                cleaned++;
                continue;
            }
            Object copy = copyRequestFragment(fragment, safe);
            if (copy == null) continue;
            try {
                fragments.set(i, copy);
                cleaned++;
            } catch (Throwable ignored) {
                if (replacementList == null) replacementList = new ArrayList<Object>(fragments);
                replacementList.set(i, copy);
                cleaned++;
            }
        }
        if (replacementList != null) setField(message, "t", replacementList);
        return cleaned;
    }

    private static Object copyRequestFragment(Object fragment, String content) {
        Integer id = asInteger(field(fragment, "b"));
        if (fragment == null || id == null) return null;
        try {
            Constructor<?> ctor = fragment.getClass().getDeclaredConstructor(int.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(id.intValue(), content);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Row persistenceRow(Object message) {
        try {
            Method method = message.getClass().getMethod("O");
            method.setAccessible(true);
            Object row = method.invoke(message);
            Integer id = asInteger(field(row, "a"));
            if (row == null || id == null) return null;
            String fragments = asString(field(row, "l"));
            fragments = sanitizeFragmentsJson(fragments);
            return new Row(id.intValue(), asInteger(field(row, "b")),
                    asString(field(row, "c")), asBooleanObject(field(row, "d")),
                    asString(field(row, "e")), asDouble(field(row, "f")),
                    asString(field(row, "g")), asInt(field(row, "h")),
                    asBoolean(field(row, "i")), asBoolean(field(row, "j")),
                    asString(field(row, "k")), fragments, asString(field(row, "m")));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean setField(Object target, String name, Object value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0d;
    }

    private static Boolean asBooleanObject(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static boolean asBoolean(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }
}
