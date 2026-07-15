package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ChatEditorThinkingRegressionTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static JSONObject fragment(JSONArray fragments, int index, String type) {
        JSONObject value = fragments.optJSONObject(index);
        check(value != null, "fragment " + index + " is not an object");
        check(type.equals(value.optString("type")),
                "fragment " + index + " type: " + value.optString("type"));
        return value;
    }

    private static void testAddingThinkKeepsResponse() throws Exception {
        JSONArray original = new JSONArray(
                "[{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"original answer\"}]");
        JSONArray fragments = ChatEditorUi.upsertFragmentContent(
                original, "ASSISTANT", "THINK", "new reasoning");
        check(fragments != null, "upsertFragmentContent returned null");
        check(fragments.length() == 2, "expected two fragments: " + fragments);

        JSONObject think = fragment(fragments, 0, "THINK");
        check(think.opt("id") instanceof Number, "THINK id is missing or not numeric");
        check(((Number) think.opt("id")).intValue() == 3, "unexpected THINK id: " + think.opt("id"));
        check("new reasoning".equals(think.optString("content")), "THINK content changed");

        JSONObject response = fragment(fragments, 1, "RESPONSE");
        check(((Number) response.opt("id")).intValue() == 2, "RESPONSE id changed");
        check("original answer".equals(response.optString("content")), "RESPONSE content was lost");
        check(ChatEditorUi.hasThinkContent(fragments), "new THINK was not detected as non-empty");
    }

    private static void testRepairingLegacyMalformedThink() throws Exception {
        JSONArray fragments = new JSONArray(
                "[{\"type\":\"THINK\",\"content\":\"old reasoning\",\"elapsed_secs\":8.5},"
                        + "{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"original answer\"}]");
        check(ChatEditorUi.repairMissingThinkFragmentIds(fragments),
                "legacy malformed THINK was not repaired");
        JSONObject think = fragment(fragments, 0, "THINK");
        check(think.opt("id") instanceof Number, "repaired THINK id is not numeric");
        check("old reasoning".equals(think.optString("content")), "repaired THINK content changed");
        check(Math.abs(think.optDouble("elapsed_secs") - 8.5d) < 0.0001d,
                "repair changed elapsed_secs");
        JSONObject response = fragment(fragments, 1, "RESPONSE");
        check("original answer".equals(response.optString("content")), "repair lost RESPONSE content");
        check(!ChatEditorUi.repairMissingThinkFragmentIds(fragments),
                "repair is not idempotent");
    }

    private static void testCustomElapsedSecondsKeepsAnswer() throws Exception {
        JSONArray fragments = new JSONArray(
                "[{\"id\":3,\"type\":\"THINK\",\"content\":\"reasoning\"},"
                        + "{\"id\":4,\"type\":\"RESPONSE\",\"content\":\"answer\"}]");
        JSONArray updated = ChatEditorUi.updateThinkElapsed(fragments, Float.valueOf(12.5f));
        check(updated != null, "updateThinkElapsed returned null");
        JSONObject think = fragment(updated, 0, "THINK");
        check(think.opt("elapsed_secs") instanceof Number, "elapsed_secs is not numeric");
        check(Math.abs(think.optDouble("elapsed_secs") - 12.5d) < 0.0001d,
                "unexpected elapsed_secs: " + think.opt("elapsed_secs"));
        check("answer".equals(fragment(updated, 1, "RESPONSE").optString("content")),
                "elapsed update lost RESPONSE content");

        ChatEditorUi.updateThinkElapsed(updated, null);
        check(!think.has("elapsed_secs"), "empty duration did not remove elapsed_secs");
        check("12.5".equals(ChatEditorUi.formatThinkElapsed(
                ChatEditorUi.parseThinkElapsed("12.5"))), "duration format changed");
    }

    private static void testInvalidElapsedSecondsAreRejected() {
        String[] invalid = {"-1", "NaN", "Infinity"};
        for (String value : invalid) {
            boolean rejected = false;
            try { ChatEditorUi.parseThinkElapsed(value); }
            catch (NumberFormatException expected) { rejected = true; }
            check(rejected, "invalid duration accepted: " + value);
        }
    }

    public static void main(String[] args) throws Exception {
        testAddingThinkKeepsResponse();
        testRepairingLegacyMalformedThink();
        testCustomElapsedSecondsKeepsAnswer();
        testInvalidElapsedSecondsAreRejected();
        System.out.println("PASS: THINK content/id/duration transforms preserve the response");
    }
}
