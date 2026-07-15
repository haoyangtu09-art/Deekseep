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
                "[{\"type\":\"THINK\",\"content\":\"old reasoning\"},"
                        + "{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"original answer\"}]");
        check(ChatEditorUi.repairMissingThinkFragmentIds(fragments),
                "legacy malformed THINK was not repaired");
        JSONObject think = fragment(fragments, 0, "THINK");
        check(think.opt("id") instanceof Number, "repaired THINK id is not numeric");
        check("old reasoning".equals(think.optString("content")), "repaired THINK content changed");
        JSONObject response = fragment(fragments, 1, "RESPONSE");
        check("original answer".equals(response.optString("content")), "repair lost RESPONSE content");
        check(!ChatEditorUi.repairMissingThinkFragmentIds(fragments),
                "repair is not idempotent");
    }

    public static void main(String[] args) throws Exception {
        testAddingThinkKeepsResponse();
        testRepairingLegacyMalformedThink();
        System.out.println("PASS: chat editor THINK JSON transform and legacy repair");
    }
}
