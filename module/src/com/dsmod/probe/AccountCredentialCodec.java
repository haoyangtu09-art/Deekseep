package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 账号导入/导出的纯 JSON 编解码层。
 *
 * 这里不碰 MMKV、SQLite 或网络，确保候选文件在产生任何副作用前就能被完整校验。
 * 导出时保留 key_user_info 的所有未知字段；导入时只校验宿主当前已知的必需字段，
 * 不会因为 DeepSeek 后续新增字段而丢失数据。
 */
final class AccountCredentialCodec {

    static final String FORMAT = "deekseep_account_export";
    static final int VERSION = 1;
    static final int MAX_ACCOUNTS = 32;
    static final int MAX_IMPORT_BYTES = 1024 * 1024;

    static final class Entry {
        final String name;
        final String id;
        final String credentialJson;

        Entry(String name, String id, String credentialJson) {
            this.name = name;
            this.id = id;
            this.credentialJson = credentialJson;
        }
    }

    static final class FormatException extends Exception {
        FormatException(String message) { super(message); }
    }

    private AccountCredentialCodec() {}

    /** 严格解析完整文档；JSON 值结束后的任何非空白字符都视为损坏。 */
    static List<Entry> parseImport(String raw) throws FormatException {
        if (raw == null || raw.trim().length() == 0) {
            throw new FormatException("文件为空");
        }
        if (raw.length() > MAX_IMPORT_BYTES) {
            throw new FormatException("文件超过 1 MiB 上限");
        }

        final Object root;
        try {
            JSONTokener tokener = new JSONTokener(raw);
            root = tokener.nextValue();
            if (tokener.nextClean() != 0) {
                throw new FormatException("JSON 末尾含有多余内容");
            }
        } catch (FormatException e) {
            throw e;
        } catch (Throwable t) {
            throw new FormatException("不是完整有效的 JSON");
        }

        List<Entry> out = new ArrayList<>();
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            if (FORMAT.equals(object.optString("format", null))) {
                parseEnvelope(object, out);
            } else {
                out.add(entryFromCredential(null, object, 1));
            }
        } else if (root instanceof JSONArray) {
            // 兼容人工整理的多账号数组，以及旧 dsmod_accounts.json 的 {cred:{...}} 包装。
            JSONArray array = (JSONArray) root;
            for (int i = 0; i < array.length(); i++) {
                JSONObject wrapper = array.optJSONObject(i);
                if (wrapper == null) {
                    throw new FormatException("第 " + (i + 1) + " 个账号不是 JSON 对象");
                }
                JSONObject credential = wrapper.optJSONObject("credential");
                if (credential == null) credential = wrapper.optJSONObject("cred");
                if (credential == null) credential = wrapper;
                String name = nullableString(wrapper, "name");
                out.add(entryFromCredential(name, credential, i + 1));
            }
        } else {
            throw new FormatException("JSON 根节点必须是账号对象或账号数组");
        }

        if (out.isEmpty()) throw new FormatException("文件中没有账号");
        if (out.size() > MAX_ACCOUNTS) {
            throw new FormatException("单次最多导入 " + MAX_ACCOUNTS + " 个账号");
        }
        Set<String> ids = new HashSet<>();
        for (Entry entry : out) {
            if (!ids.add(entry.id)) {
                throw new FormatException("文件中包含重复账号：" + shortId(entry.id));
            }
        }
        return out;
    }

    private static void parseEnvelope(JSONObject root, List<Entry> out) throws FormatException {
        Object version = root.opt("version");
        if (!(version instanceof Number)
                || ((Number) version).doubleValue() != VERSION) {
            throw new FormatException("不支持的账号文件版本");
        }
        JSONArray accounts = root.optJSONArray("accounts");
        if (accounts == null) throw new FormatException("缺少 accounts 数组");
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject wrapper = accounts.optJSONObject(i);
            if (wrapper == null) {
                throw new FormatException("第 " + (i + 1) + " 个账号不是 JSON 对象");
            }
            JSONObject credential = wrapper.optJSONObject("credential");
            if (credential == null) {
                throw new FormatException("第 " + (i + 1) + " 个账号缺少 credential 对象");
            }
            out.add(entryFromCredential(nullableString(wrapper, "name"), credential, i + 1));
        }
    }

    static Entry entryFromCredential(String preferredName, JSONObject credential, int index)
            throws FormatException {
        validateCredential(credential, index);
        String id = credential.optString("id", "").trim();
        String name = preferredName == null ? null : preferredName.trim();
        if (name == null || name.length() == 0) name = labelOf(credential);
        if (name == null || name.trim().length() == 0) name = shortId(id);
        return new Entry(name, id, credential.toString());
    }

    /** 完整 key_user_info 的最低契约。未知字段原样保留。 */
    static void validateCredential(JSONObject o, int index) throws FormatException {
        String prefix = index > 0 ? "第 " + index + " 个账号" : "账号";
        if (o == null) throw new FormatException(prefix + "不是 JSON 对象");

        String id = requiredString(o, "id", prefix);
        if (id.length() < 8 || id.length() > 128) {
            throw new FormatException(prefix + "的 id 长度不正确");
        }
        String token = requiredString(o, "token", prefix);
        if (token.length() < 16 || token.length() > 16384) {
            throw new FormatException(prefix + "的 token 长度不正确");
        }

        requireNullableString(o, "email", prefix);
        requireNullableString(o, "mobile_number", prefix);
        requireIntegralNumber(o, "status", prefix);
        if (!(o.opt("chat_status") instanceof JSONObject)) {
            throw new FormatException(prefix + "的 chat_status 必须是对象");
        }
        Object profilesValue = o.opt("id_profiles");
        if (!(profilesValue instanceof JSONArray)) {
            throw new FormatException(prefix + "的 id_profiles 必须是数组");
        }
        JSONArray profiles = (JSONArray) profilesValue;
        for (int i = 0; i < profiles.length(); i++) {
            if (!(profiles.opt(i) instanceof JSONObject)) {
                throw new FormatException(prefix + "的 id_profiles[" + i + "] 必须是对象");
            }
        }
        if (!(o.opt("need_birthday") instanceof Boolean)) {
            throw new FormatException(prefix + "的 need_birthday 必须是布尔值");
        }
    }

    static String buildExport(List<Entry> entries) throws FormatException {
        if (entries == null || entries.isEmpty()) throw new FormatException("没有选择账号");
        if (entries.size() > MAX_ACCOUNTS) throw new FormatException("选择的账号过多");
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("exported_at", System.currentTimeMillis());
            JSONArray accounts = new JSONArray();
            for (int i = 0; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                JSONObject credential = new JSONObject(entry.credentialJson);
                validateCredential(credential, i + 1);
                JSONObject wrapper = new JSONObject();
                wrapper.put("name", entry.name == null ? shortId(entry.id) : entry.name);
                wrapper.put("credential", credential);
                accounts.put(wrapper);
            }
            root.put("accounts", accounts);
            return root.toString(2) + "\n";
        } catch (FormatException e) {
            throw e;
        } catch (Throwable t) {
            throw new FormatException("无法生成账号 JSON");
        }
    }

    static String suggestFileName(List<Entry> entries) {
        List<String> names = new ArrayList<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (names.size() >= 3) break;
                String clean = safeName(e == null ? null : e.name);
                if (clean.length() > 0) names.add(clean);
            }
        }
        StringBuilder base = new StringBuilder();
        for (String name : names) {
            if (base.length() > 0) base.append('_');
            base.append(name);
        }
        int count = entries == null ? 0 : entries.size();
        if (base.length() == 0) base.append("DeepSeek账号");
        if (count > names.size()) base.append("_等").append(count).append("个账号");
        if (base.length() > 96) base.setLength(96);
        return base.append("_DeepSeek账号.txt").toString();
    }

    private static String requiredString(JSONObject o, String key, String prefix)
            throws FormatException {
        if (!o.has(key) || !(o.opt(key) instanceof String)) {
            throw new FormatException(prefix + "缺少字符串字段 " + key);
        }
        String value = ((String) o.opt(key)).trim();
        if (value.length() == 0) throw new FormatException(prefix + "的 " + key + " 不能为空");
        return value;
    }

    private static void requireNullableString(JSONObject o, String key, String prefix)
            throws FormatException {
        if (!o.has(key)) throw new FormatException(prefix + "缺少字段 " + key);
        Object value = o.opt(key);
        if (value != JSONObject.NULL && !(value instanceof String)) {
            throw new FormatException(prefix + "的 " + key + " 必须是字符串或 null");
        }
    }

    private static void requireIntegralNumber(JSONObject o, String key, String prefix)
            throws FormatException {
        if (!o.has(key) || !(o.opt(key) instanceof Number)) {
            throw new FormatException(prefix + "缺少数字字段 " + key);
        }
        double value = ((Number) o.opt(key)).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)) {
            throw new FormatException(prefix + "的 " + key + " 必须是整数");
        }
    }

    private static String nullableString(JSONObject o, String key) throws FormatException {
        if (!o.has(key) || o.isNull(key)) return null;
        Object value = o.opt(key);
        if (!(value instanceof String)) throw new FormatException(key + " 必须是字符串");
        return (String) value;
    }

    private static String labelOf(JSONObject credential) {
        JSONArray profiles = credential.optJSONArray("id_profiles");
        if (profiles != null) {
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                String name = profile == null ? null : profile.optString("name", null);
                if (name != null && name.trim().length() > 0) return name.trim();
            }
        }
        String mobile = credential.isNull("mobile_number")
                ? null : credential.optString("mobile_number", null);
        if (mobile != null && mobile.trim().length() > 0) return mobile.trim();
        String email = credential.isNull("email") ? null : credential.optString("email", null);
        if (email != null && email.trim().length() > 0) return email.trim();
        return shortId(credential.optString("id", null));
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        clean = clean.replaceAll("[. ]+$", "");
        if (clean.length() > 40) clean = clean.substring(0, 40);
        return clean;
    }

    private static String shortId(String id) {
        if (id == null) return "未知账号";
        String value = id.trim().toLowerCase(Locale.US);
        return value.length() > 8 ? value.substring(0, 8) : value;
    }
}
