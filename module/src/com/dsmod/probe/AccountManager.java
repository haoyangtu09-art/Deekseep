package com.dsmod.probe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 多账号管理数据层。零混淆符号依赖：
 *  - 凭证读写走 MMKV 的 SharedPreferences 接口（MMKV implements SharedPreferences）；
 *    只有“取默认实例”按方法签名反射定位（0 参静态、返回 MMKV 的方法，即混淆后的 defaultMMKV/k()）。
 *  - app_user_info 多账号表用纯 SQLite 维护（id 为主键，切号只改 MMKV 指向 + upsert 目标行，不删他号）。
 *  - 账号槽持久化到模块可写的 App 私有目录 dsmod_accounts.json。
 *
 * DeepSeek 判活只读 MMKV key_user_info；app_user_info 是所有已知账号的缓存表。
 * 因此“切换账号” = 把目标账号 JSON 写进 key_user_info + 重启进程让 e5.a() 以新账号冷启动。
 */
final class AccountManager {

    static final String PKG = "com.deepseek.chat";
    static final String FILES_DIR = "/data/data/" + PKG + "/files";
    static final String DB_DIR = "/data/data/" + PKG + "/databases";
    static final String SLOTS_FILE = FILES_DIR + "/dsmod_accounts.json";
    static final String USER_DB = DB_DIR + "/deepseek_chat.db";
    static final String MMKV_KEY = "key_user_info";
    private static final String VERIFY_URL = "https://chat.deepseek.com/api/v0/users/current";
    private static final String RANGERS_PREFS = "applog_stats_240734";
    private static final String RANGERS_ID_KEY = "bd_did";
    private static final int VERIFY_MAX_ATTEMPTS = 2;
    private static final long VERIFY_MIN_INTERVAL_MS = 1500L;
    private static final long VERIFY_DEFAULT_RETRY_MS = 2500L;
    private static final long VERIFY_MAX_INLINE_RETRY_MS = 10000L;
    private static final Object VERIFY_PACING_LOCK = new Object();
    private static long lastVerifyStartedAtMs;

    private AccountManager() {}

    // ── 账号模型 ─────────────────────────────────────────────
    static final class Account {
        String id;          // uuid
        String token;
        String label;       // 展示名：微信名 > 手机号 > 邮箱 > 短 uuid
        String avatar;      // id_profiles[0].picture，可空
        String provider;    // 登录方式：WECHAT / GOOGLE / APPLE / 手机(null)
        String credJson;    // 原始 key_user_info JSON，切换时原样写回
        long savedAt;
        boolean current;    // 是否当前登录账号
    }

    static final class ServerValidation {
        final boolean valid;
        final boolean retryable;
        final String error;

        private ServerValidation(boolean valid, boolean retryable, String error) {
            this.valid = valid;
            this.retryable = retryable;
            this.error = error;
        }

        static ServerValidation ok() { return new ServerValidation(true, false, null); }
        static ServerValidation fail(String error) {
            return new ServerValidation(false, false, error);
        }
        static ServerValidation retryable(String error) {
            return new ServerValidation(false, true, error);
        }
    }

    // ── MMKV 默认实例（SharedPreferences 视图） ───────────────
    // MMKV 内部方法名被 R8 混淆，但它实现了 SharedPreferences，故 get/put 用稳定接口；
    // 仅“取默认实例”按签名反射：唯一的 0 参静态且返回 MMKV 自身类型的方法。
    static SharedPreferences defaultMmkv(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.tencent.mmkv.MMKV", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getReturnType() == c) {
                    m.setAccessible(true);
                    Object inst = m.invoke(null);
                    if (inst instanceof SharedPreferences) return (SharedPreferences) inst;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 读/写当前登录凭证 ─────────────────────────────────────
    static String readCurrentJson(ClassLoader cl) {
        SharedPreferences sp = defaultMmkv(cl);
        if (sp != null) {
            try {
                String v = sp.getString(MMKV_KEY, null);
                if (v != null && v.length() > 0) return v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static String currentId(ClassLoader cl) {
        return idOf(readCurrentJson(cl));
    }

    // 写入目标账号为当前登录态：MMKV key_user_info + upsert app_user_info 行。
    static boolean writeCurrentJson(ClassLoader cl, String json) {
        if (json == null) return false;
        boolean ok = false;
        SharedPreferences sp = defaultMmkv(cl);
        if (sp != null) {
            try { sp.edit().putString(MMKV_KEY, json).apply(); ok = true; } catch (Throwable ignored) {}
        }
        upsertUserRow(json);
        return ok;
    }

    // 清空当前登录态（用于“添加账号”：重启后宿主检测 key_user_info 为空自动进登录页）。
    // 只清 MMKV 指向，不动 app_user_info 各行，避免丢失已知账号缓存。
    static boolean clearCurrent(ClassLoader cl) {
        SharedPreferences sp = defaultMmkv(cl);
        if (sp != null) {
            try { sp.edit().remove(MMKV_KEY).apply(); return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    // ── 账号槽持久化 ─────────────────────────────────────────
    // 文件结构： [ {"savedAt":long, "cred": {...原始 key_user_info...}}, ... ]，按 id 去重。
    static List<Account> listSlots() {
        List<Account> out = new ArrayList<>();
        String raw = readFile(SLOTS_FILE);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                JSONObject cred = w.optJSONObject("cred");
                if (cred == null) continue;
                Account a = fromCred(cred.toString());
                if (a == null) continue;
                a.savedAt = w.optLong("savedAt", 0);
                out.add(a);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // 把一份凭证 JSON 存进槽（同 id 覆盖）。返回是否成功写盘。
    static boolean upsertSlot(String credJson) {
        String id = idOf(credJson);
        if (id == null) return false;
        JSONArray out = new JSONArray();
        boolean replaced = false;
        String raw = readFile(SLOTS_FILE);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject w = arr.optJSONObject(i);
                    if (w == null) continue;
                    JSONObject cred = w.optJSONObject("cred");
                    String wid = cred == null ? null : cred.optString("id", null);
                    if (id.equals(wid)) {
                        out.put(wrap(credJson));   // 用最新凭证覆盖
                        replaced = true;
                    } else {
                        out.put(w);
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (!replaced) out.put(wrap(credJson));
        return writeFileAtomic(SLOTS_FILE, out.toString());
    }

    static boolean removeSlot(String id) {
        if (id == null) return false;
        String raw = readFile(SLOTS_FILE);
        if (raw == null) return false;
        JSONArray out = new JSONArray();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject w = arr.optJSONObject(i);
                if (w == null) continue;
                JSONObject cred = w.optJSONObject("cred");
                String wid = cred == null ? null : cred.optString("id", null);
                if (!id.equals(wid)) out.put(w);
            }
        } catch (Throwable ignored) { return false; }
        return writeFileAtomic(SLOTS_FILE, out.toString());
    }

    private static JSONObject wrap(String credJson) {
        JSONObject w = new JSONObject();
        try {
            w.put("savedAt", System.currentTimeMillis());
            w.put("cred", new JSONObject(credJson));
        } catch (Throwable ignored) {}
        return w;
    }

    // ── 高层操作 ─────────────────────────────────────────────
    // 懒快照：把当前登录账号存进槽（面板每次打开时调，覆盖旧 token 保鲜）。
    static void snapshotCurrent(ClassLoader cl) {
        String cur = readCurrentJson(cl);
        if (cur != null) upsertSlot(cur);
    }

    // 返回带 current 标记、当前号置顶的账号列表（已快照当前号）。
    static List<Account> accountsForUi(ClassLoader cl) {
        snapshotCurrent(cl);
        String curId = currentId(cl);
        List<Account> list = listSlots();
        Account cur = null;
        List<Account> rest = new ArrayList<>();
        for (Account a : list) {
            if (curId != null && curId.equals(a.id)) { a.current = true; cur = a; }
            else rest.add(a);
        }
        List<Account> ordered = new ArrayList<>();
        if (cur != null) ordered.add(cur);
        ordered.addAll(rest);
        return ordered;
    }

    // 切换到已有账号：先快照当前号（保鲜），再把目标写为当前登录态。调用方随后重启进程。
    static boolean switchTo(ClassLoader cl, String id) {
        if (id == null) return false;
        snapshotCurrent(cl);
        for (Account a : listSlots()) {
            if (id.equals(a.id)) return writeCurrentJson(cl, a.credJson);
        }
        return false;
    }

    // 准备添加账号：快照当前号，清 key_user_info。调用方随后重启进程 → 宿主进登录页。
    static boolean prepareAddAccount(ClassLoader cl) {
        snapshotCurrent(cl);
        return clearCurrent(cl);
    }

    /**
     * 把已经全部通过服务器验证的候选账号一次性合并进槽文件。
     * 调用方必须先完成所有账号的网络验证；该方法再次做本地格式校验，并以临时文件原子替换，
     * 从而避免多账号导入在中途失败时只写入一部分。
     */
    static boolean importValidated(List<AccountCredentialCodec.Entry> entries) {
        if (entries == null || entries.isEmpty()) return false;
        LinkedHashMap<String, JSONObject> merged = new LinkedHashMap<>();
        String raw = readFile(SLOTS_FILE);
        if (raw != null) {
            try {
                JSONArray old = new JSONArray(raw);
                for (int i = 0; i < old.length(); i++) {
                    JSONObject wrapper = old.optJSONObject(i);
                    JSONObject credential = wrapper == null ? null : wrapper.optJSONObject("cred");
                    String id = credential == null ? null : credential.optString("id", null);
                    if (id != null && id.length() > 0) merged.put(id, wrapper);
                }
            } catch (Throwable ignored) {}
        }

        try {
            for (int i = 0; i < entries.size(); i++) {
                AccountCredentialCodec.Entry entry = entries.get(i);
                JSONObject credential = new JSONObject(entry.credentialJson);
                AccountCredentialCodec.validateCredential(credential, i + 1);
                merged.put(entry.id, wrap(entry.credentialJson));
            }
        } catch (Throwable t) {
            return false;
        }

        JSONArray out = new JSONArray();
        for (Map.Entry<String, JSONObject> item : merged.entrySet()) out.put(item.getValue());
        if (!writeFileAtomic(SLOTS_FILE, out.toString())) return false;

        // 槽文件发布成功以后才更新宿主的账号缓存表；不会改当前 MMKV 指向，也不会自动切号。
        for (AccountCredentialCodec.Entry entry : entries) upsertUserRow(entry.credentialJson);
        return true;
    }

    /**
     * 用候选 token 请求 DeepSeek 的“当前用户”接口。只有 HTTP 200、外层 code=0、
     * 内层 biz_code=0 才算有效；HTTP 200 + 40002 等错误绝不放行。该官方接口的
     * biz_data 允许为空；如果响应实际携带用户 id，则额外要求它与候选 id 完全一致。
     * 请求头与宿主 App 的网络层保持一致（包括 Bearer 鉴权、设备 ID 和时区）；批量导入
     * 会限制请求起始频率，真实 HTTP 429
     * 最多按 Retry-After 自动重试一次。本方法必须在后台线程调用，且不会写入候选凭证、
     * 响应正文或 token。
     */
    static ServerValidation validateWithServer(Context context, String credJson) {
        final JSONObject credential;
        final String token;
        final String expectedId;
        try {
            credential = new JSONObject(credJson);
            AccountCredentialCodec.validateCredential(credential, 0);
            token = credential.getString("token");
            expectedId = credential.getString("id");
        } catch (Throwable t) {
            return ServerValidation.fail("凭证格式校验失败");
        }

        String version = appVersion(context);
        for (int attempt = 0; attempt < VERIFY_MAX_ATTEMPTS; attempt++) {
            HttpURLConnection connection = null;
            InputStream stream = null;
            try {
                awaitVerifyPacing();
                connection = (HttpURLConnection) new URL(VERIFY_URL).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setUseCaches(false);
                Map<String, String> headers = validationHeaders(
                        token,
                        version,
                        Build.VERSION.SDK_INT,
                        hostLocale(context),
                        validationRangersId(context),
                        validationTimezoneOffsetSeconds(
                                TimeZone.getDefault(), System.currentTimeMillis()));
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }

                int http = connection.getResponseCode();
                String body = "";
                if (http == HttpURLConnection.HTTP_OK) {
                    stream = connection.getInputStream();
                    body = readBoundedUtf8(stream, 512 * 1024);
                }
                ServerValidation result = parseServerResponse(http, body, expectedId);
                if (!result.retryable || attempt + 1 >= VERIFY_MAX_ATTEMPTS) return result;

                long delayMs = retryDelayMillis(connection.getHeaderField("Retry-After"));
                if (delayMs < 0L) return result;
                Thread.sleep(delayMs);
            } catch (InterruptedException t) {
                Thread.currentThread().interrupt();
                return ServerValidation.fail("账号校验已取消");
            } catch (java.net.SocketTimeoutException t) {
                return ServerValidation.fail("连接 DeepSeek 服务器超时");
            } catch (Throwable t) {
                return ServerValidation.fail("无法连接或解析 DeepSeek 校验结果");
            } finally {
                if (stream != null) try { stream.close(); } catch (Throwable ignored) {}
                if (connection != null) connection.disconnect();
            }
        }
        return ServerValidation.fail("DeepSeek 暂时限流，请稍后重试");
    }

    static String validationUserAgent(String version, int sdkInt) {
        String value = version == null ? "" : version.trim();
        if (value.length() == 0) value = "2.2.2";
        return "DeepSeek/" + value + " Android/" + Math.max(1, sdkInt);
    }

    static String validationLocale(Locale locale) {
        if (locale == null) return "zh_CN";
        String language = locale.getLanguage();
        if (language == null || language.length() == 0) language = "zh";
        String country = locale.getCountry();
        return country == null || country.length() == 0
                ? language : language + "_" + country;
    }

    /**
     * Keep this map aligned with DeepSeek's Ktor client. In 2.2.2 the account token is installed
     * by Ktor's bearer-auth provider as "Authorization: Bearer ...". x-auth-token belongs to a
     * separate telemetry stack and makes /users/current return code 40002.
     */
    static Map<String, String> validationHeaders(String token, String version, int sdkInt,
                                                  String locale, String rangersId,
                                                  int timezoneOffsetSeconds) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", validationUserAgent(version, sdkInt));
        headers.put("Referer", "https://chat.deepseek.com");
        headers.put("Authorization", "Bearer " + token);
        headers.put("x-client-platform", "android");
        headers.put("x-client-bundle-id", PKG);
        headers.put("x-client-version", version == null || version.trim().length() == 0
                ? "2.2.2" : version.trim());
        headers.put("x-client-locale", locale == null || locale.trim().length() == 0
                ? "zh_CN" : locale.trim());
        headers.put("x-client-timezone-offset", String.valueOf(timezoneOffsetSeconds));
        if (rangersId != null && rangersId.trim().length() > 0) {
            headers.put("x-rangers-id", rangersId.trim());
        }
        return headers;
    }

    static int validationTimezoneOffsetSeconds(TimeZone timezone, long nowMs) {
        TimeZone value = timezone == null ? TimeZone.getDefault() : timezone;
        return value.getOffset(nowMs) / 1000;
    }

    private static String hostLocale(Context context) {
        try {
            int id = context.getResources().getIdentifier("locale", "string", PKG);
            if (id != 0) {
                String value = context.getString(id);
                if (value != null && value.trim().length() > 0) return value.trim();
            }
        } catch (Throwable ignored) {}
        return validationLocale(Locale.getDefault());
    }

    private static String validationRangersId(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(RANGERS_PREFS, Context.MODE_PRIVATE);
            try {
                String value = prefs.getString(RANGERS_ID_KEY, null);
                if (value != null && value.trim().length() > 0) return value.trim();
            } catch (ClassCastException ignored) {}
            Object value = prefs.getAll().get(RANGERS_ID_KEY);
            return value == null ? null : String.valueOf(value).trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static ServerValidation parseServerResponse(int http, String body, String expectedId) {
        if (http == 429) {
            return ServerValidation.retryable(
                    "DeepSeek 暂时限流（HTTP 429），请稍后再导入");
        }
        if (http != HttpURLConnection.HTTP_OK) {
            return ServerValidation.fail("服务器校验失败（HTTP " + http + "）");
        }
        try {
            JSONObject response = new JSONObject(body);
            int code = response.optInt("code", Integer.MIN_VALUE);
            if (code != 0) {
                if (code == 40002 || code == 401 || code == 403) {
                    return ServerValidation.fail(
                            "凭证已失效或被服务器拒绝（code=" + code + "）");
                }
                return ServerValidation.fail("服务器未确认该凭证有效（code=" + code + "）");
            }
            JSONObject data = response.optJSONObject("data");
            if (data == null || !data.has("biz_code")) {
                return ServerValidation.fail("服务器校验响应缺少 biz_code");
            }
            int bizCode = data.optInt("biz_code", Integer.MIN_VALUE);
            if (bizCode != 0) {
                return ServerValidation.fail(
                        "服务器未确认该凭证有效（biz_code=" + bizCode + "）");
            }
            String actualId = data.optString("id", null);
            JSONObject bizData = data.optJSONObject("biz_data");
            if ((actualId == null || actualId.length() == 0) && bizData != null) {
                actualId = bizData.optString("id", null);
            }
            if (actualId != null && actualId.length() > 0
                    && expectedId != null && !expectedId.equals(actualId)) {
                return ServerValidation.fail("服务器返回的账号与文件中的账号不一致");
            }
            return ServerValidation.ok();
        } catch (Throwable t) {
            return ServerValidation.fail("无法解析 DeepSeek 校验结果");
        }
    }

    private static void awaitVerifyPacing() throws InterruptedException {
        synchronized (VERIFY_PACING_LOCK) {
            long now = System.nanoTime() / 1000000L;
            long waitMs = lastVerifyStartedAtMs == 0L ? 0L
                    : lastVerifyStartedAtMs + VERIFY_MIN_INTERVAL_MS - now;
            if (waitMs > 0L) Thread.sleep(waitMs);
            lastVerifyStartedAtMs = System.nanoTime() / 1000000L;
        }
    }

    static long retryDelayMillis(String retryAfter) {
        if (retryAfter == null || retryAfter.trim().length() == 0) {
            return VERIFY_DEFAULT_RETRY_MS;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            if (seconds < 0L || seconds > VERIFY_MAX_INLINE_RETRY_MS / 1000L) return -1L;
            return Math.max(VERIFY_MIN_INTERVAL_MS, seconds * 1000L);
        } catch (Throwable ignored) {
            // 无法可靠解释服务端要求的等待时间时不立即重试，避免放大限流。
            return -1L;
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PKG, 0);
            if (info.versionName != null && info.versionName.length() > 0) return info.versionName;
        } catch (Throwable ignored) {}
        return "2.2.2";
    }

    static String readBoundedUtf8(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new Exception("response too large");
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    // ── app_user_info 表维护 ─────────────────────────────────
    static void upsertUserRow(String json) {
        if (json == null) return;
        File f = new File(USER_DB);
        if (!f.exists()) return;
        SQLiteDatabase d = null;
        try {
            JSONObject o = new JSONObject(json);
            d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            d.execSQL("INSERT OR REPLACE INTO app_user_info"
                            + "(id,token,email,mobile_number,status,id_profiles,chat_status,need_birthday)"
                            + " VALUES(?,?,?,?,?,?,?,?)",
                    new Object[]{
                            o.optString("id", null),
                            o.optString("token", null),
                            o.isNull("email") ? null : o.optString("email", null),
                            o.isNull("mobile_number") ? null : o.optString("mobile_number", null),
                            o.optInt("status", 0),
                            o.isNull("id_profiles") ? null : String.valueOf(o.opt("id_profiles")),
                            o.isNull("chat_status") ? null : String.valueOf(o.opt("chat_status")),
                            o.optBoolean("need_birthday", false) ? 1 : 0
                    });
        } catch (Throwable ignored) {
        } finally {
            if (d != null) try { d.close(); } catch (Throwable ignored) {}
        }
    }

    // ── 解析辅助 ─────────────────────────────────────────────
    static String idOf(String credJson) {
        if (credJson == null) return null;
        try { return new JSONObject(credJson).optString("id", null); }
        catch (Throwable t) { return null; }
    }

    static Account fromCred(String credJson) {
        if (credJson == null) return null;
        try {
            JSONObject o = new JSONObject(credJson);
            String id = o.optString("id", null);
            if (id == null || id.length() == 0) return null;
            Account a = new Account();
            a.id = id;
            a.token = o.optString("token", null);
            a.credJson = credJson;
            a.label = labelOf(o);
            a.avatar = avatarOf(o);
            a.provider = providerOf(o);
            return a;
        } catch (Throwable t) { return null; }
    }

    static String labelOf(JSONObject o) {
        // 微信名
        try {
            JSONArray profs = o.optJSONArray("id_profiles");
            if (profs != null) {
                for (int i = 0; i < profs.length(); i++) {
                    JSONObject p = profs.optJSONObject(i);
                    if (p == null) continue;
                    String name = p.optString("name", null);
                    if (name != null && name.trim().length() > 0) return name.trim();
                }
            }
        } catch (Throwable ignored) {}
        String mobile = o.isNull("mobile_number") ? null : o.optString("mobile_number", null);
        if (mobile != null && mobile.length() > 0) return mobile;
        String email = o.isNull("email") ? null : o.optString("email", null);
        if (email != null && email.length() > 0) return email;
        String id = o.optString("id", "");
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    // 登录方式：优先 id_profiles[0].provider（大写 WECHAT/GOOGLE/APPLE）；
    // 无第三方档案则按 手机号>邮箱 推断（有邮箱无档案通常是 GOOGLE/APPLE 邮箱登录，统一走灰白）。
    static String providerOf(JSONObject o) {
        try {
            JSONArray profs = o.optJSONArray("id_profiles");
            if (profs != null) {
                for (int i = 0; i < profs.length(); i++) {
                    JSONObject p = profs.optJSONObject(i);
                    if (p == null) continue;
                    String pv = p.optString("provider", null);
                    if (pv != null && pv.trim().length() > 0) return pv.trim().toUpperCase();
                }
            }
        } catch (Throwable ignored) {}
        String mobile = o.isNull("mobile_number") ? null : o.optString("mobile_number", null);
        if (mobile != null && mobile.length() > 0) return "PHONE";
        return null;
    }

    static String avatarOf(JSONObject o) {
        try {
            JSONArray profs = o.optJSONArray("id_profiles");
            if (profs != null) {
                for (int i = 0; i < profs.length(); i++) {
                    JSONObject p = profs.optJSONObject(i);
                    if (p == null) continue;
                    String pic = p.optString("picture", null);
                    if (pic != null && pic.startsWith("http")) return pic;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 进程重启 ─────────────────────────────────────────────
    // 切号/添加号后调用：安排 250ms 后用启动 Intent 重新拉起，再杀当前进程，
    // 让 MMKV/e5 以新的 key_user_info 冷启动。
    static void restartApp(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                int flags = PendingIntent.FLAG_CANCEL_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pi = PendingIntent.getActivity(ctx, 0x5AC7, i, flags);
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.setExact(AlarmManager.RTC, System.currentTimeMillis() + 250, pi);
            }
        } catch (Throwable ignored) {}
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    // ── 文件 IO ──────────────────────────────────────────────
    private static String readFile(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean writeFileAtomic(String path, String content) {
        java.io.FileOutputStream out = null;
        File tmp = null;
        try {
            File f = new File(path);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            tmp = new File(path + ".tmp");
            out = new java.io.FileOutputStream(tmp);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) {}
            out.close();
            out = null;
            return tmp.renameTo(f);
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            if (tmp != null && tmp.exists()) try { tmp.delete(); } catch (Throwable ignored) {}
        }
    }
}
