package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NativeApiPatchDecoderRegressionTest {
    public static void main(String[] args) throws Exception {
        separatesThinkingAndResponseAcrossBareDeltas();
        preservesOrdinaryResponseDeltas();
        System.out.println("NativeApiPatchDecoderRegressionTest OK");
    }

    private static void separatesThinkingAndResponseAcrossBareDeltas() throws Exception {
        NativeApiPatchDecoder decoder = new NativeApiPatchDecoder();
        JSONObject think = new JSONObject().put("v", new JSONObject().put("response",
                new JSONObject().put("fragments", new JSONArray().put(new JSONObject()
                        .put("id", 2).put("type", "THINK").put("content", "我们")))));
        NativeApiPatchDecoder.Delta first = decoder.decode(think);
        check("我们".equals(first.reasoningSet), "THINK snapshot was not reasoning");
        check(empty(first.text) && empty(first.textSet), "THINK snapshot leaked into text");

        NativeApiPatchDecoder.Delta second = decoder.decode(new JSONObject()
                .put("p", "response/fragments/-1/content")
                .put("o", "APPEND").put("v", "先分析"));
        check("先分析".equals(second.reasoning), "explicit THINK delta was not reasoning");
        NativeApiPatchDecoder.Delta third = decoder.decode(new JSONObject().put("v", "过程"));
        check("过程".equals(third.reasoning), "bare THINK continuation became text");

        NativeApiPatchDecoder.Delta switched = decoder.decode(new JSONObject()
                .put("p", "response/fragments").put("o", "APPEND")
                .put("v", new JSONArray().put(new JSONObject().put("id", 3)
                        .put("type", "RESPONSE").put("content", "F"))));
        check("F".equals(switched.text), "first RESPONSE token was dropped");
        check(empty(switched.reasoning), "RESPONSE token remained in thinking");

        // Exact no-op frame shape observed immediately after the fragment switch.
        NativeApiPatchDecoder.Delta fourth = decoder.decode(new JSONObject()
                .put("p", "response/fragments/-1/content").put("v", "INAL"));
        NativeApiPatchDecoder.Delta fifth = decoder.decode(new JSONObject().put("v", "_ONLY"));
        check("INAL".equals(fourth.text), "no-op RESPONSE content frame was dropped");
        check("_ONLY".equals(fifth.text), "bare RESPONSE continuation was dropped");
    }

    private static void preservesOrdinaryResponseDeltas() throws Exception {
        NativeApiPatchDecoder decoder = new NativeApiPatchDecoder();
        decoder.decode(new JSONObject().put("v", new JSONObject().put("response",
                new JSONObject().put("fragments", new JSONArray().put(new JSONObject()
                        .put("type", "RESPONSE").put("content", ""))))));
        NativeApiPatchDecoder.Delta explicit = decoder.decode(new JSONObject()
                .put("p", "response/fragments/-1/content")
                .put("o", "APPEND").put("v", "HELLO"));
        NativeApiPatchDecoder.Delta bare = decoder.decode(new JSONObject().put("v", "_WORLD"));
        check("HELLO".equals(explicit.text) && "_WORLD".equals(bare.text),
                "ordinary RESPONSE stream changed");
        check(empty(explicit.reasoning) && empty(bare.reasoning),
                "ordinary RESPONSE stream became reasoning");
    }

    private static boolean empty(String value) {
        return value == null || value.length() == 0;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
