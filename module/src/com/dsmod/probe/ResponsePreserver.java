package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable counterpart of the live CONTENT_FILTER guards.
 *
 * The server history endpoint can return the already-finalised template on the next process start,
 * when the original mv object no longer exists.  We therefore serialise only messages for which a
 * real filter event was observed, using DeepSeek's own kv serializer, and restore that exact object
 * before history rendering or persistence.  Files remain in DeepSeek's private files directory.
 */
final class ResponsePreserver {
    private static final String DEFAULT_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_preserved_responses";
    private static final String TEST_DIR_PROPERTY = "deekseep.response_preserver_dir";
    private static final int SCHEMA = 1;
    private static final int MAX_HOST_JSON = 4 * 1024 * 1024;
    private static final Object IO_LOCK = new Object();

    private ResponsePreserver() {}

    static boolean isFilteredRecord(String status, String quasiStatus, String fragments) {
        if (containsFilterStatus(status) || containsFilterStatus(quasiStatus)) return true;
        if (fragments == null || fragments.length() == 0) return false;
        try {
            JSONArray array = new JSONArray(fragments);
            for (int i = 0; i < array.length(); i++) {
                JSONObject fragment = array.optJSONObject(i);
                if (fragment != null
                        && "TEMPLATE_RESPONSE".equals(fragment.optString("type"))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean isFilteredHostMessage(Object message) {
        if (message == null) return false;
        Object row = call(message, "O");
        return isFilteredRecord(callString(message, "D"), callString(message, "x"),
                asString(field(row, "l")));
    }

    /** Save the full static kv only after Main has observed an actual filter event. */
    static boolean saveHostMessage(ClassLoader cl, String sid, Object message) {
        if (!validSid(sid) || message == null) return false;
        Integer messageId = callInteger(message, "u");
        if (messageId == null || messageId.intValue() <= 0
                || !"ASSISTANT".equals(callString(message, "A"))
                || isFilteredHostMessage(message)) return false;

        Object row = call(message, "O");
        String fragments = asString(field(row, "l"));
        int score = originalContentScore(fragments);
        if (score <= 0) return false;
        int stateQuality = stateQuality(callString(message, "D"), callString(message, "x"));

        Object staticMessage = call(message, "a");
        if (staticMessage == null) return false;
        String hostJson = encodeHostMessage(cl, staticMessage);
        if (hostJson == null || hostJson.length() == 0 || hostJson.length() > MAX_HOST_JSON) {
            return false;
        }

        synchronized (IO_LOCK) {
            try {
                File dir = storageDir();
                if (!dir.exists() && !dir.mkdirs()) return false;
                File target = recordFile(dir, sid, messageId.intValue());
                JSONObject old = readRecord(target);
                if (old != null && old.optString("host_message", "").length() > 0) {
                    int oldScore = old.optInt("score", 0);
                    int oldStateQuality = old.optInt("state_quality", 0);
                    if (oldScore > score
                            || (oldScore == score && oldStateQuality >= stateQuality)) return true;
                }

                JSONObject root = new JSONObject();
                root.put("schema", SCHEMA);
                root.put("sid", sid);
                root.put("message_id", messageId.intValue());
                root.put("score", score);
                root.put("state_quality", stateQuality);
                root.put("saved_at", System.currentTimeMillis());
                root.put("host_message", hostJson);
                File temp = new File(dir, target.getName() + ".tmp");
                FileWriter writer = new FileWriter(temp, false);
                writer.write(root.toString());
                writer.close();
                if (target.exists() && !target.delete()) {
                    temp.delete();
                    return false;
                }
                return temp.renameTo(target);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /** Returns a verified preserved kv only when the incoming object is a filter replacement. */
    static Object restoreHostMessage(ClassLoader cl, String sid, Object incoming) {
        if (!validSid(sid) || incoming == null || !isFilteredHostMessage(incoming)) return null;
        Integer messageId = callInteger(incoming, "u");
        if (messageId == null || messageId.intValue() <= 0) return null;
        JSONObject record;
        synchronized (IO_LOCK) {
            record = readRecord(recordFile(storageDir(), sid, messageId.intValue()));
        }
        if (!validRecord(record, sid, messageId.intValue())) return null;
        String hostJson = record.optString("host_message", "");
        if (hostJson.length() == 0 || hostJson.length() > MAX_HOST_JSON) return null;
        Object restored = decodeHostMessage(cl, hostJson);
        if (restored == null
                || !messageId.equals(callInteger(restored, "u"))
                || !"ASSISTANT".equals(callString(restored, "A"))
                || isFilteredHostMessage(restored)) return null;
        Object row = call(restored, "O");
        return originalContentScore(asString(field(row, "l"))) > 0 ? restored : null;
    }

    /** Replace filtered kv entries before pw0 leaves its constructor hook. */
    static int restoreHistoryResponse(ClassLoader cl, Object response) {
        Object session = field(response, "a");
        String sid = asString(field(session, "a"));
        Object value = field(response, "b");
        if (!validSid(sid) || !(value instanceof List)) return 0;
        List messages = (List) value;
        ArrayList<Object> replacement = null;
        int restored = 0;
        for (int i = 0; i < messages.size(); i++) {
            Object incoming = messages.get(i);
            Object original = restoreHostMessage(cl, sid, incoming);
            if (original == null) continue;
            try {
                messages.set(i, original);
                restored++;
            } catch (Throwable immutable) {
                if (replacement == null) replacement = new ArrayList<Object>(messages);
                replacement.set(i, original);
            }
        }
        if (replacement != null && setField(response, "b", replacement)) {
            for (int i = 0; i < replacement.size(); i++) {
                if (replacement.get(i) != messages.get(i)) restored++;
            }
        }
        return restored;
    }

    /** Replace filtered sl8/rl8 values before gm8/fm8 writes them to WCDB. */
    static int restoreRepositoryRows(ClassLoader cl, String sid, Object rowsValue) {
        if (!validSid(sid) || !(rowsValue instanceof List)) return 0;
        int restored = 0;
        for (Object incoming : (List<?>) rowsValue) {
            Integer messageId = asInteger(field(incoming, "a"));
            String status = asString(field(incoming, "e"));
            String fragments = asString(field(incoming, "l"));
            if (messageId == null || messageId.intValue() <= 0
                    || !"ASSISTANT".equals(asString(field(incoming, "c")))
                    || !isFilteredRecord(status, null, fragments)) continue;
            Object original = loadPreservedMessage(cl, sid, messageId.intValue());
            Object originalRow = call(original, "O");
            if (originalRow == null
                    || !messageId.equals(asInteger(field(originalRow, "a")))
                    || isFilteredRecord(asString(field(originalRow, "e")), null,
                            asString(field(originalRow, "l")))) continue;
            if (!setField(incoming, "l", field(originalRow, "l"))) continue;
            if (!setField(incoming, "e", field(originalRow, "e"))) continue;
            for (String name : new String[]{"b", "c", "d", "f", "g", "h", "i", "j", "k", "m"}) {
                setField(incoming, name, field(originalRow, name));
            }
            restored++;
        }
        return restored;
    }

    static void forgetSession(String sid) {
        if (!validSid(sid)) return;
        synchronized (IO_LOCK) {
            File[] files = storageDir().listFiles();
            if (files == null) return;
            String prefix = sid + "__";
            for (File file : files) {
                if (file != null && file.isFile() && file.getName().startsWith(prefix)) {
                    file.delete();
                }
            }
        }
    }

    private static Object loadPreservedMessage(ClassLoader cl, String sid, int messageId) {
        JSONObject record;
        synchronized (IO_LOCK) {
            record = readRecord(recordFile(storageDir(), sid, messageId));
        }
        if (!validRecord(record, sid, messageId)) return null;
        Object restored = decodeHostMessage(cl, record.optString("host_message", ""));
        if (restored == null || messageId != intValue(callInteger(restored, "u"), -1)
                || !"ASSISTANT".equals(callString(restored, "A"))
                || isFilteredHostMessage(restored)) return null;
        Object row = call(restored, "O");
        return originalContentScore(asString(field(row, "l"))) > 0 ? restored : null;
    }

    private static boolean validRecord(JSONObject record, String sid, int messageId) {
        return record != null && record.optInt("schema", 0) == SCHEMA
                && sid.equals(record.optString("sid", ""))
                && record.optInt("message_id", -1) == messageId;
    }

    private static String encodeHostMessage(ClassLoader cl, Object message) {
        for (String[] pair : new String[][]{{"cc4", "jv"}, {"x94", "hv"}}) {
            try {
                Object json = staticField(cl.loadClass(pair[0]), "a");
                Object serializer = staticField(cl.loadClass(pair[1]), "a");
                Method encode = method(json, "c", 2);
                Object value = encode == null ? null : encode.invoke(json, serializer, message);
                if (value instanceof String) return (String) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object decodeHostMessage(ClassLoader cl, String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_HOST_JSON) return null;
        for (String[] pair : new String[][]{{"cc4", "jv"}, {"x94", "hv"}}) {
            try {
                Object json = staticField(cl.loadClass(pair[0]), "a");
                Object serializer = staticField(cl.loadClass(pair[1]), "a");
                Method decode = method(json, "b", 2);
                Object decoded = decode == null ? null : decode.invoke(json, serializer, value);
                if (decoded != null) return decoded;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int originalContentScore(String fragments) {
        if (fragments == null || fragments.length() == 0) return 0;
        try {
            JSONArray array = new JSONArray(fragments);
            int score = 0;
            boolean response = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject fragment = array.optJSONObject(i);
                if (fragment == null) continue;
                String type = fragment.optString("type", "");
                if ("TEMPLATE_RESPONSE".equals(type)) return 0;
                String content = fragment.optString("content", "");
                if ("RESPONSE".equals(type) && content.trim().length() > 0) {
                    response = true;
                    score += 1000 + content.length();
                } else if (("THINK".equals(type) || "SEARCH".equals(type))
                        && content.length() > 0) {
                    score += content.length();
                }
            }
            return response ? score : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean containsFilterStatus(String value) {
        return value != null && value.contains("CONTENT_FILTER");
    }

    private static int stateQuality(String status, String quasiStatus) {
        String value = status == null || status.length() == 0 ? quasiStatus : status;
        if (value == null) return 0;
        if (value.contains("FINISHED")) return 3;
        if (value.contains("INTERRUPTED") || value.contains("ERROR")) return 2;
        if (value.contains("STREAMING")) return 1;
        return 0;
    }

    private static File storageDir() {
        String override = System.getProperty(TEST_DIR_PROPERTY);
        return new File(override == null || override.length() == 0 ? DEFAULT_DIR : override);
    }

    private static File recordFile(File dir, String sid, int messageId) {
        return new File(dir, sid + "__" + messageId + ".json");
    }

    private static JSONObject readRecord(File file) {
        if (file == null || !file.isFile() || file.length() <= 0
                || file.length() > MAX_HOST_JSON + 4096L) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            StringBuilder out = new StringBuilder((int) Math.min(file.length(), 8192L));
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count > 0) out.append(buffer, 0, count);
                if (out.length() > MAX_HOST_JSON + 4096) return null;
            }
            return new JSONObject(out.toString());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) try { reader.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean validSid(String sid) {
        return sid != null && sid.matches("[0-9a-fA-F-]{36}");
    }

    private static Object staticField(Class<?> type, String name) throws Throwable {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Method method(Object target, String name, int parameters) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Object call(Object target, String name) {
        Method method = method(target, name, 0);
        if (method == null) return null;
        try { return method.invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    private static String callString(Object target, String name) {
        Object value = call(target, name);
        return value == null ? null : String.valueOf(value);
    }

    private static Integer callInteger(Object target, String name) {
        return asInteger(call(target, name));
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean setField(Object target, String name, Object value) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
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

    private static int intValue(Integer value, int fallback) {
        return value == null ? fallback : value.intValue();
    }
}
