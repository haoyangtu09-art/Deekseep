package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public final class HistoryBridgeRegressionTest {
    static final class Fragment {
        final String a;
        final int b;
        final String c;
        Fragment(String type, int id, String content) { a = type; b = id; c = content; }
        Fragment(int id, String content) { this("REQUEST", id, content); }
    }

    static final class PersistedRow {
        int a; Integer b; String c; Boolean d; String e; double f; String g;
        int h; boolean i; boolean j; String k; String l; String m;
    }

    static final class Message {
        final int id;
        final Integer parent;
        final String role;
        List<Fragment> t;
        Message(int id, Integer parent, String role, Fragment... fragments) {
            this.id = id; this.parent = parent; this.role = role;
            this.t = new ArrayList<>(Arrays.asList(fragments));
        }
        public PersistedRow O() throws Exception {
            PersistedRow row = new PersistedRow();
            row.a = id; row.b = parent; row.c = role; row.d = Boolean.FALSE;
            row.e = "FINISHED"; row.f = 1.0d; row.h = 0;
            JSONArray json = new JSONArray();
            for (Fragment fragment : t) {
                json.put(new JSONObject().put("id", fragment.b).put("type", fragment.a)
                        .put("content", fragment.c));
            }
            row.l = json.toString();
            return row;
        }
    }

    static final class Session {
        String a;
        Integer c;
        Integer d;
        Session(String sid, Integer current) { this(sid, null, current); }
        Session(String sid, Integer version, Integer current) { a = sid; c = version; d = current; }
    }

    static final class Response {
        Session a;
        List<Message> b;
        String c;
        Response(Session session, List<Message> messages) { this(session, messages, "REPLACE"); }
        Response(Session session, List<Message> messages, String control) {
            a = session; b = messages; c = control;
        }
    }

    static final class RepositoryRow { String l; RepositoryRow(String json) { l = json; } }

    static final class NativeSession {
        String a;
        Integer n;
        LinkedHashMap<Integer, Message> f = new LinkedHashMap<>();
        Integer current;
        NativeSession(String sid, Integer version, Integer current, Message... messages) {
            a = sid; n = version; this.current = current;
            for (Message message : messages) f.put(message.id, message);
        }
        public Integer t() { return current; }
        public Integer e() { return current; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int occurrences(String text, String needle) {
        int count = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { count++; at += needle.length(); }
        return count;
    }

    private static void testPromptPolicyIsIdempotent() {
        String one = "<system>\nold\n</system>\n\nuser text";
        String two = "<system>\nolder\n</system>\n\n" + one;
        check("user text".equals(HistoryBridge.stripInjectedSystemPrompts(one)), "single wrapper remained");
        check("user text".equals(HistoryBridge.stripInjectedSystemPrompts(two)), "nested wrapper remained");
        String wrapped = HistoryBridge.wrapSystemPrompt("new", two);
        check(occurrences(wrapped, "<system>") == 1, "wrap duplicated system prefix");
        check(wrapped.endsWith("user text"), "wrap changed the user text");
        check("plain".equals(HistoryBridge.stripInjectedSystemPrompts("plain")), "plain text changed");
        check("  indented".equals(HistoryBridge.stripInjectedSystemPrompts(
                "<system>\np\n</system>\n\n  indented")), "leading user spaces were lost");
        String indentedWrapper = "  <system>\np\n</system>\n\nuser text";
        check(indentedWrapper.equals(HistoryBridge.stripInjectedSystemPrompts(indentedWrapper)),
                "user-authored indented XML was removed");
        String malformed = "<system>\np\n</system>\nuser text";
        check(malformed.equals(HistoryBridge.stripInjectedSystemPrompts(malformed)),
                "non-injector wrapper format was removed");
        check("user text".equals(HistoryBridge.stripInjectedSystemPrompts(
                "<system>\r\np\r\n</system>\r\n\r\nuser text")), "CRLF wrapper remained");
    }

    private static void testJsonSanitizerPreservesOtherFragments() throws Exception {
        String json = "[{\"id\":1,\"type\":\"FILE\",\"content\":\"metadata\"},"
                + "{\"id\":2,\"type\":\"REQUEST\",\"content\":\"<system>\\np\\n</system>\\n\\nhello\"}]";
        JSONArray safe = new JSONArray(HistoryBridge.sanitizeFragmentsJson(json));
        check("metadata".equals(safe.getJSONObject(0).getString("content")), "FILE fragment changed");
        check("hello".equals(safe.getJSONObject(1).getString("content")), "REQUEST was not cleaned");
    }

    private static void testPw0SnapshotAndParentChain() throws Exception {
        Message user = new Message(1, null, "USER",
                new Fragment("REQUEST", 1, "<system>\np\n</system>\n\nquestion"));
        Message answer = new Message(2, 1, "ASSISTANT", new Fragment("RESPONSE", 1, "answer"));
        HistoryBridge.Result result = HistoryBridge.processHistoryResponse(
                new Response(new Session("snapshot-test", 2), Arrays.asList(user, answer)));
        check(result.cleaned == 1, "in-memory REQUEST was not cleaned");
        check(result.rows == 2, "persistence rows were not captured");
        check("question".equals(user.t.get(0).c), "host fragment still contains the prompt");

        HistoryBridge.Snapshot snapshot = HistoryBridge.snapshot("snapshot-test");
        check(snapshot != null && snapshot.rows.size() == 2, "snapshot missing");
        check(!snapshot.rows.get(0).fragments.contains("<system>"), "snapshot persisted the prompt");
        List<ChatEditorUi.Msg> thread = ChatEditorUi.loadSnapshotThread(snapshot);
        check(thread.size() == 2, "snapshot parent chain has wrong size");
        check("question".equals(thread.get(0).body), "user body changed");
        check("answer".equals(thread.get(1).body), "assistant body changed");
    }

    private static void testVersionedMergeAndReplacement() throws Exception {
        Message user = new Message(10, null, "USER", new Fragment("REQUEST", 1, "question"));
        Message answer = new Message(11, 10, "ASSISTANT", new Fragment("RESPONSE", 1, "answer"));
        HistoryBridge.processHistoryResponse(new Response(new Session("merge-test", 4, 11),
                Arrays.asList(user, answer), "REPLACE"));
        Message followup = new Message(12, 11, "USER", new Fragment("REQUEST", 1, "follow-up"));
        HistoryBridge.processHistoryResponse(new Response(new Session("merge-test", 5, 12),
                Arrays.asList(followup), "MERGE"));
        HistoryBridge.Snapshot merged = HistoryBridge.snapshot("merge-test");
        check(merged.complete && merged.version == 5 && merged.rows.size() == 3,
                "MERGE delta replaced its complete baseline");
        check(ChatEditorUi.loadSnapshotThread(merged).size() == 3, "merged parent chain is incomplete");

        Message stale = new Message(99, null, "USER", new Fragment("REQUEST", 1, "stale"));
        HistoryBridge.processHistoryResponse(new Response(new Session("merge-test", 3, 99),
                Arrays.asList(stale), "REPLACE"));
        check(HistoryBridge.snapshot("merge-test").rows.size() == 3,
                "older response overwrote a newer snapshot");

        HistoryBridge.processHistoryResponse(new Response(new Session("orphan-merge", 1, 1),
                Arrays.asList(stale), "MERGE"));
        HistoryBridge.Snapshot orphan = HistoryBridge.snapshot("orphan-merge");
        check(orphan != null && !orphan.complete, "orphan MERGE was marked complete");
        check(ChatEditorUi.loadSnapshotThread(orphan).size() == 1,
                "incomplete MERGE was not available for read-only display");

        HistoryBridge.processHistoryResponse(new Response(new Session("merge-test", 6, null),
                new ArrayList<Message>(), "REPLACE"));
        HistoryBridge.Snapshot empty = HistoryBridge.snapshot("merge-test");
        check(empty.complete && empty.rows.isEmpty() && empty.currentMessageId == null,
                "empty REPLACE did not clear stale rows/current message");
    }

    private static void testNativeTpSnapshotForNewConversation() throws Exception {
        Message baseline = new Message(21, null, "USER", new Fragment("REQUEST", 1, "old question"));
        HistoryBridge.processHistoryResponse(new Response(new Session("native-test", 8, 21),
                Arrays.asList(baseline), "REPLACE"));
        Message user = new Message(21, null, "USER",
                new Fragment("REQUEST", 1, "<system>\np\n</system>\n\nnew question"));
        Message answer = new Message(22, 21, "ASSISTANT", new Fragment("RESPONSE", 1, "new answer"));
        HistoryBridge.Result result = HistoryBridge.processNativeSessions(Arrays.asList(
                new NativeSession("native-test", 8, 22, user, answer)));
        check(result.cleaned == 1 && result.rows == 2, "live tp messages were not captured");
        HistoryBridge.Snapshot snapshot = HistoryBridge.snapshot("native-test");
        check(snapshot != null && !snapshot.complete && snapshot.version == 8
                        && snapshot.rows.size() == 2,
                "same-version live tp update did not replace the stale baseline read-only");
        List<ChatEditorUi.Msg> thread = ChatEditorUi.loadSnapshotThread(snapshot);
        check(thread.size() == 2 && "new question".equals(thread.get(0).body),
                "new conversation is not readable from tp memory");

        HistoryBridge.processHistoryResponse(new Response(new Session("native-test", 8, 22),
                Arrays.asList(user, answer), "REPLACE"));
        check(HistoryBridge.snapshot("native-test").complete,
                "same-version pw0 REPLACE did not promote the live snapshot");
        HistoryBridge.processNativeSession(Arrays.asList(
                new NativeSession("native-test", 8, 22, user, answer)), "native-test");
        check(HistoryBridge.snapshot("native-test").complete,
                "an unchanged tp refresh downgraded the complete pw0 snapshot");
    }

    private static void testTargetedNativeTpSnapshotIsIsolated() throws Exception {
        Message otherUser = new Message(31, null, "USER",
                new Fragment("REQUEST", 1, "<system>\np\n</system>\n\nother question"));
        Message targetUser = new Message(41, null, "USER",
                new Fragment("REQUEST", 1, "<system>\np\n</system>\n\ntarget question"));
        Message targetAnswer = new Message(42, 41, "ASSISTANT",
                new Fragment("RESPONSE", 1, "target answer"));
        List<NativeSession> sessions = Arrays.asList(
                new NativeSession("targeted-other", 1, 31, otherUser),
                new NativeSession("targeted-target", 2, 42, targetUser, targetAnswer));

        HistoryBridge.Result result = HistoryBridge.processNativeSession(
                sessions, "targeted-target");
        check(result.cleaned == 1 && result.rows == 2,
                "targeted native refresh did not capture only the requested session");
        check(HistoryBridge.snapshot("targeted-target") != null,
                "targeted native snapshot is missing");
        check(HistoryBridge.snapshot("targeted-other") == null,
                "targeted native refresh polluted another session snapshot");
        check(otherUser.t.get(0).c.startsWith("<system>"),
                "targeted native refresh mutated an unrelated session");

        HistoryBridge.Result missing = HistoryBridge.processNativeSession(
                sessions, "targeted-missing");
        check(missing.cleaned == 0 && missing.rows == 0,
                "missing targeted session did not return an empty result");
        check(HistoryBridge.snapshot("targeted-missing") == null,
                "missing targeted session created a snapshot");
    }

    private static void testRepositoryDefenceInDepth() throws Exception {
        RepositoryRow row = new RepositoryRow(
                "[{\"id\":1,\"type\":\"REQUEST\",\"content\":\"<system>\\np\\n</system>\\n\\nbody\"}]");
        Object[] args = new Object[7];
        args[4] = new ArrayList<>(Arrays.asList(row));
        check(HistoryBridge.sanitizeRepositoryRows(args) == 1, "repository row not reported clean");
        check("body".equals(new JSONArray(row.l).getJSONObject(0).getString("content")),
                "repository row still contains prompt");
        check(HistoryBridge.sanitizeRepositoryRows(args) == 0, "repository cleaning is not idempotent");
    }

    public static void main(String[] args) throws Exception {
        testPromptPolicyIsIdempotent();
        testJsonSanitizerPreservesOtherFragments();
        testPw0SnapshotAndParentChain();
        testVersionedMergeAndReplacement();
        testNativeTpSnapshotForNewConversation();
        testTargetedNativeTpSnapshotIsIsolated();
        testRepositoryDefenceInDepth();
        System.out.println("PASS: online history is sanitized and available to the local editor");
    }
}
