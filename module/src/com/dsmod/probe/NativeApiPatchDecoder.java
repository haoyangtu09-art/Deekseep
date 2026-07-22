package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Stateful decoder for DeepSeek's completion patch stream.
 *
 * <p>The host sends the active fragment type only when a THINK/RESPONSE fragment is created.
 * Following token frames normally contain only {@code {"v":"..."}} or a generic
 * {@code response/fragments/-1/content} path.  Treating every such frame as public text leaks the
 * chain of thought and also drops the first response token when the stream changes fragments.
 * One decoder instance therefore belongs to one collected native Flow.</p>
 */
final class NativeApiPatchDecoder {
    static final class Delta {
        String text = "";
        String reasoning = "";
        String textSet;
        String reasoningSet;
    }

    private enum Channel { TEXT, REASONING }

    private Channel activeChannel = Channel.TEXT;
    private String lastContentPath = "";

    Delta decode(Object json) {
        Delta out = new Delta();
        collect(json, "", false, out);
        return out;
    }

    private void collect(Object node, String inheritedPath, boolean appendContext, Delta out) {
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collect(array.opt(i), inheritedPath, appendContext, out);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;

        JSONObject object = (JSONObject) node;
        boolean hasExplicitPath = object.has("p") || object.has("path");
        String explicitPath = object.optString("p", object.optString("path", ""));
        String path = explicitPath.length() > 0 ? explicitPath : inheritedPath;
        String op = object.optString("o", object.optString("op", ""));
        boolean childAppend = appendContext || "APPEND".equalsIgnoreCase(op);

        // A fragments snapshot or a newly appended fragment is the authoritative channel switch.
        // The content on an APPENDed fragment is itself a delta; content in a snapshot is a SET.
        Object fragmentContent = object.opt("content");
        String fragmentType = object.optString("type", "");
        if (fragmentContent instanceof String && isFragmentObject(fragmentType, inheritedPath)) {
            activeChannel = channelForFragment(fragmentType);
            lastContentPath = canonicalContentPath(inheritedPath);
            add(out, activeChannel, (String) fragmentContent, appendContext);
        } else if (fragmentType.length() > 0
                && inheritedPath.toLowerCase(Locale.US).contains("fragments")) {
            activeChannel = channelForFragment(fragmentType);
            lastContentPath = canonicalContentPath(inheritedPath);
        }

        Object value = object.has("v") ? object.opt("v") : object.opt("value");
        if (value instanceof String) {
            boolean bareContinuation = !hasExplicitPath && inheritedPath.length() == 0
                    && (object.length() == 1 || (object.length() == 2 && object.has("value")));
            String valuePath = path;
            if (bareContinuation && lastContentPath.length() > 0) {
                valuePath = lastContentPath;
            }
            if (isContentPath(valuePath)) {
                if (hasExplicitPath && explicitPath.length() > 0) {
                    lastContentPath = explicitPath;
                }
                Channel channel = channelForPath(valuePath, activeChannel);
                boolean set = "SET".equalsIgnoreCase(op);
                // DeepSeek sometimes omits o=APPEND on the first token after it appends a new
                // RESPONSE fragment.  A scalar content patch without an op is still a delta.
                boolean append = "APPEND".equalsIgnoreCase(op) || op.length() == 0;
                if (set || append) add(out, channel, (String) value, append);
            }
        }

        JSONArray names = object.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            if ("p".equals(name) || "path".equals(name)
                    || "o".equals(name) || "op".equals(name)
                    || "content".equals(name) || "type".equals(name)) continue;
            Object child = object.opt(name);
            if (!(child instanceof JSONObject) && !(child instanceof JSONArray)) continue;
            String childPath;
            if (("v".equals(name) || "value".equals(name)) && path.length() > 0) {
                childPath = path;
            } else {
                childPath = path.length() == 0 ? name : path + "/" + name;
            }
            collect(child, childPath, childAppend, out);
        }
    }

    private static boolean isFragmentObject(String type, String inheritedPath) {
        if (type == null || type.length() == 0) return false;
        String lower = type.toLowerCase(Locale.US);
        return inheritedPath.toLowerCase(Locale.US).contains("fragments")
                || lower.contains("think") || lower.contains("reason")
                || lower.contains("response") || lower.contains("text");
    }

    private static Channel channelForFragment(String type) {
        String lower = type == null ? "" : type.toLowerCase(Locale.US);
        return lower.contains("think") || lower.contains("reason")
                ? Channel.REASONING : Channel.TEXT;
    }

    private static Channel channelForPath(String path, Channel fallback) {
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        if (lower.contains("think") || lower.contains("reason")) return Channel.REASONING;
        return fallback == null ? Channel.TEXT : fallback;
    }

    private static boolean isContentPath(String path) {
        if (path == null || path.length() == 0) return false;
        String lower = path.toLowerCase(Locale.US);
        return lower.contains("think") || lower.contains("reason")
                || lower.endsWith("/content") || lower.equals("content")
                || lower.contains("/text");
    }

    private static String canonicalContentPath(String inheritedPath) {
        String path = inheritedPath == null ? "" : inheritedPath;
        if (path.endsWith("/content")) return path;
        if (path.toLowerCase(Locale.US).contains("fragments")) {
            return path + (path.endsWith("/") ? "content" : "/content");
        }
        return "response/fragments/-1/content";
    }

    private static void add(Delta out, Channel channel, String value, boolean append) {
        if (value == null) return;
        if (channel == Channel.REASONING) {
            if (append) out.reasoning += value;
            else out.reasoningSet = concat(out.reasoningSet, value);
        } else {
            if (append) out.text += value;
            else out.textSet = concat(out.textSet, value);
        }
    }

    private static String concat(String current, String value) {
        return current == null ? value : current + value;
    }
}
