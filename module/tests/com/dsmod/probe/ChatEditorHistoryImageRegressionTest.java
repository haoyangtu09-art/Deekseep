package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatEditorHistoryImageRegressionTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject image(int id, String name, String path) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("status", "SUCCESS")
                .put("file_name", name)
                .put("signed_path", path)
                .put("is_image", true)
                .put("audit_result", "pass")
                .put("width", 100)
                .put("height", 80);
    }

    private static void testReplaceImagesPreservesEverythingElse() throws Exception {
        JSONArray original = new JSONArray()
                .put(new JSONObject().put("id", 7).put("type", "FILE").put("files", new JSONArray()
                        .put(image(11, "old.png", "/file?old"))
                        .put(new JSONObject().put("id", 12).put("file_name", "notes.pdf")
                                .put("is_image", false))))
                .put(new JSONObject().put("id", 8).put("type", "REQUEST")
                        .put("content", "hello"));
        String before = original.toString();
        List<JSONObject> replacement = Collections.singletonList(
                image(13, "new.png", "/file?new"));
        JSONArray updated = ChatEditorUi.replaceImageFiles(original, replacement);

        check(before.equals(original.toString()), "input fragments were mutated");
        check(updated.length() == 2, "unexpected fragment count: " + updated);
        JSONObject file = updated.getJSONObject(0);
        check(file.getInt("id") == 7, "FILE fragment id changed");
        JSONArray files = file.getJSONArray("files");
        check(files.length() == 2, "non-image attachment was lost: " + files);
        check("notes.pdf".equals(files.getJSONObject(0).getString("file_name")),
                "non-image attachment order/content changed");
        check("new.png".equals(files.getJSONObject(1).getString("file_name")),
                "replacement image was not attached");
        check("hello".equals(updated.getJSONObject(1).getString("content")),
                "REQUEST content changed");
    }

    private static void testAddAndRemoveImageFragments() throws Exception {
        JSONArray requestOnly = new JSONArray()
                .put(new JSONObject().put("id", 3).put("type", "REQUEST").put("content", "x"));
        JSONArray added = ChatEditorUi.replaceImageFiles(requestOnly,
                Collections.singletonList(image(21, "added.jpg", "/file?added")));
        check(added.length() == 2, "FILE fragment not added");
        check("FILE".equals(added.getJSONObject(0).getString("type")),
                "FILE fragment must precede REQUEST");
        check(added.getJSONObject(0).getInt("id") == 4, "new FILE id is not unique");

        JSONArray removed = ChatEditorUi.replaceImageFiles(added,
                Collections.<JSONObject>emptyList());
        check(removed.length() == 1, "empty image-only FILE fragment was retained");
        check("REQUEST".equals(removed.getJSONObject(0).getString("type")),
                "REQUEST was lost while removing images");
    }

    private static ChatEditorUi.Msg msg(long id, String fragments) {
        ChatEditorUi.Msg value = new ChatEditorUi.Msg();
        value.id = id;
        value.role = id % 2 == 0 ? "ASSISTANT" : "USER";
        value.rawFragments = fragments;
        return value;
    }

    private static void testLatestSnapshotSelection() {
        List<ChatEditorUi.Msg> local = new ArrayList<>();
        local.add(msg(1, "local-user"));
        local.add(msg(2, "local-answer"));
        List<ChatEditorUi.Msg> online = new ArrayList<>();
        online.add(msg(1, "local-user"));
        online.add(msg(2, "streaming-new-answer"));
        HistoryBridge.Snapshot snapshot = new HistoryBridge.Snapshot(
                "sid", Integer.valueOf(9), Integer.valueOf(2), false,
                Collections.<HistoryBridge.Row>emptyList());

        check(ChatEditorUi.shouldPreferSnapshot(8, local, snapshot, online),
                "newer snapshot was not preferred");
        check(ChatEditorUi.shouldPreferSnapshot(9, local, snapshot, online),
                "equal-version in-flight fragments were not refreshed");
        check(!ChatEditorUi.shouldPreferSnapshot(ChatEditorUi.FREEZE_VERSION,
                        local, snapshot, online),
                "frozen local edit was overridden");
    }

    private static void testInitialConversationFragments() throws Exception {
        JSONArray user = ChatEditorUi.initialMessageFragments("USER", "hello");
        check("REQUEST".equals(user.getJSONObject(0).getString("type")),
                "new user conversation did not create REQUEST");
        JSONArray assistant = ChatEditorUi.initialMessageFragments("ASSISTANT", "");
        check("RESPONSE".equals(assistant.getJSONObject(0).getString("type")),
                "new AI conversation did not create RESPONSE");
        check("".equals(assistant.getJSONObject(0).getString("content")),
                "blank AI response is not supported");
    }

    public static void main(String[] args) throws Exception {
        testReplaceImagesPreservesEverythingElse();
        testAddAndRemoveImageFragments();
        testLatestSnapshotSelection();
        testInitialConversationFragments();
        System.out.println("PASS: latest-history, blank-conversation and uploaded-image transforms");
    }
}
