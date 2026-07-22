package com.dsmod.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Language policy shared by every maintained Deekseep UI.
 *
 * <p>With no manual override, the injected UI follows DeepSeek's own application locale on every
 * process start/resume.  Any {@code zh} locale selects Chinese; every other locale deliberately
 * falls back to English so a newly supported host language can never strand the module in Chinese.
 * The small override file lives in the DeepSeek process because that is where the real module page
 * is rendered.  The standalone module activity safely falls back to its own configuration when the
 * target-private file is inaccessible.</p>
 */
final class UiLanguage {
    static final String MODE_AUTO = "auto";
    static final String MODE_CHINESE = "chinese";
    static final String MODE_ENGLISH = "english";

    private static final String MODE_FILE =
            "/data/data/com.deepseek.chat/files/deekseep_language";
    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String HOST_LANGUAGE_KEY = "key_app_language";
    private static volatile String mode = MODE_AUTO;
    private static volatile boolean chinese;
    private static volatile String detectedLanguage = "en";
    private static volatile boolean initialized;

    private UiLanguage() {}

    /**
     * Refresh using the correct policy for the process that owns {@code context}.
     * Injected pages follow DeepSeek; the standalone module app follows Android itself.
     */
    static synchronized void refresh(Context context) {
        if (context != null && TARGET_PACKAGE.equals(context.getPackageName())) {
            refreshHost(context);
        } else {
            refreshSystem(context);
        }
    }

    static synchronized void refreshHost(Context context) {
        String detected = detectHostLanguage(context);
        String stored = readModeFile();
        mode = normalizeMode(stored);
        detectedLanguage = detected;
        chinese = effectiveChinese(mode, detected);
        initialized = true;
    }

    static synchronized void refreshSystem(Context context) {
        String detected = detectSystemLanguage(context);
        // The launcher activity is a separate app surface.  Its language is intentionally not
        // affected by the override stored inside DeepSeek's private directory.
        mode = MODE_AUTO;
        detectedLanguage = detected;
        chinese = isChineseLanguage(detected);
        initialized = true;
    }

    static void ensure(Context context) {
        if (!initialized) refresh(context);
    }

    static boolean isChinese(Context context) {
        ensure(context);
        return chinese;
    }

    static boolean isEnglish(Context context) {
        return !isChinese(context);
    }

    static String currentMode(Context context) {
        ensure(context);
        return mode;
    }

    static String detectedLanguage(Context context) {
        ensure(context);
        return detectedLanguage;
    }

    static boolean setMode(Context context, String requested) {
        String normalized = normalizeMode(requested);
        try {
            File file = new File(MODE_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
            FileOutputStream out = new FileOutputStream(file, false);
            out.write(normalized.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
            refresh(context);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String text(Context context, String zh, String en) {
        return isChinese(context) ? value(zh) : value(en);
    }

    static String text(String zh, String en) {
        return chinese ? value(zh) : value(en);
    }

    /** Translate a module-generated runtime status or formatted message. */
    static String dynamic(Context context, CharSequence value) {
        if (value == null) return "";
        return isChinese(context) ? value.toString()
                : UiLanguageCatalog.toEnglish(value.toString());
    }

    static String dynamic(CharSequence value) {
        if (value == null) return "";
        return chinese ? value.toString() : UiLanguageCatalog.toEnglish(value.toString());
    }

    static Toast toast(Context context, CharSequence value, int duration) {
        return Toast.makeText(context, dynamic(context, value), duration);
    }

    /**
     * Localize compile-time labels without touching chat/account text loaded from user data.
     * Android/Dex const-string values are interned; database and network strings normally are not.
     */
    static void localizeTree(Context context, View root) {
        if (root == null || isChinese(context)) return;
        if (root instanceof TextView) {
            TextView textView = (TextView) root;
            CharSequence current = textView.getText();
            String translated = translateLiteral(current);
            if (translated != null) textView.setText(translated);
            CharSequence hint = textView.getHint();
            translated = translateLiteral(hint);
            if (translated != null) textView.setHint(translated);
        }
        CharSequence description = root.getContentDescription();
        String translatedDescription = translateLiteral(description);
        if (translatedDescription != null) root.setContentDescription(translatedDescription);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                localizeTree(context, group.getChildAt(i));
            }
        }
    }

    static String modeTitle(Context context, String value) {
        String normalized = normalizeMode(value);
        if (MODE_CHINESE.equals(normalized)) return "Chinese";
        if (MODE_ENGLISH.equals(normalized)) return "English";
        return text(context, "跟随 DeepSeek（自动）", "Follow DeepSeek (Auto)");
    }

    static String effectiveSummary(Context context) {
        ensure(context);
        String effective = chinese ? "Chinese" : "English";
        if (!MODE_AUTO.equals(mode)) {
            return text(context, "手动选择：", "Manual: ") + effective;
        }
        return text(context, "自动检测 DeepSeek：", "Detected from DeepSeek: ")
                + effective + " (" + detectedLanguage + ")";
    }

    static String normalizeMode(String value) {
        if (MODE_CHINESE.equalsIgnoreCase(value)) return MODE_CHINESE;
        if (MODE_ENGLISH.equalsIgnoreCase(value)) return MODE_ENGLISH;
        return MODE_AUTO;
    }

    static boolean isChineseLanguage(String language) {
        if (language == null) return false;
        String normalized = language.trim().toLowerCase(Locale.US).replace('_', '-');
        return "zh".equals(normalized) || normalized.startsWith("zh-");
    }

    /** Pure policy helper kept separate so locale fallback behavior is regression-testable. */
    static boolean effectiveChinese(String requestedMode, String detected) {
        String normalized = normalizeMode(requestedMode);
        return MODE_CHINESE.equals(normalized)
                || (MODE_AUTO.equals(normalized) && isChineseLanguage(detected));
    }

    static String detectLanguage(Configuration configuration) {
        try {
            Locale locale;
            if (configuration == null) {
                locale = Locale.getDefault();
            } else if (Build.VERSION.SDK_INT >= 24) {
                LocaleList locales = configuration.getLocales();
                locale = locales == null || locales.isEmpty() ? Locale.getDefault() : locales.get(0);
            } else {
                //noinspection deprecation
                locale = configuration.locale;
            }
            String language = locale == null ? null : locale.getLanguage();
            return language == null || language.trim().length() == 0
                    ? "en" : language.toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return "en";
        }
    }

    /**
     * DeepSeek 2.x stores its own language tag in the default MMKV.  Reading this stable key is
     * more reliable than Activity configuration: the Play build applies its locale through an
     * AppCompat wrapper and does not populate Android's per-app locale service on all versions.
     */
    static String detectHostLanguage(Context context) {
        if (context != null) {
            try {
                SharedPreferences mmkv = AccountManager.defaultMmkv(context.getClassLoader());
                if (mmkv != null) {
                    String tag = mmkv.getString(HOST_LANGUAGE_KEY, null);
                    if (tag == null || tag.trim().length() == 0) {
                        return detectSystemLanguage(context);
                    }
                    return languageFromTag(tag);
                }
            } catch (Throwable ignored) {}

            // Resource marker fallback used by both mainland and Play resources (for example
            // en_US, zh_CN, ja).  It also covers future host builds whose MMKV implementation is
            // renamed while their localized resources remain intact.
            try {
                int id = context.getResources().getIdentifier(
                        "locale", "string", TARGET_PACKAGE);
                if (id != 0) {
                    String marker = context.getString(id);
                    if (marker != null && marker.trim().length() > 0) {
                        return languageFromTag(marker);
                    }
                }
            } catch (Throwable ignored) {}

            try {
                return detectLanguage(context.getResources().getConfiguration());
            } catch (Throwable ignored) {}
        }
        return detectSystemLanguage(context);
    }

    static String detectSystemLanguage(Context context) {
        try {
            Configuration system = Resources.getSystem().getConfiguration();
            String detected = detectLanguage(system);
            if (detected != null && detected.length() > 0) return detected;
        } catch (Throwable ignored) {}
        try {
            if (context != null) {
                return detectLanguage(context.getResources().getConfiguration());
            }
        } catch (Throwable ignored) {}
        return detectLanguage(null);
    }

    static String languageFromTag(String tag) {
        if (tag == null) return "en";
        String normalized = tag.trim().replace('_', '-');
        if (normalized.length() == 0) return "en";
        try {
            Locale parsed = Locale.forLanguageTag(normalized);
            String language = parsed == null ? null : parsed.getLanguage();
            if (language != null && language.trim().length() > 0) {
                return language.toLowerCase(Locale.US);
            }
        } catch (Throwable ignored) {}
        int separator = normalized.indexOf('-');
        String language = separator < 0 ? normalized : normalized.substring(0, separator);
        return language.length() == 0 ? "en" : language.toLowerCase(Locale.US);
    }

    private static String translateLiteral(CharSequence value) {
        if (!(value instanceof String)) return null;
        String input = (String) value;
        if (!UiLanguageCatalog.mightTranslate(input)) return null;
        try {
            if (input != input.intern()) return null;
        } catch (Throwable ignored) {
            return null;
        }
        String translated = UiLanguageCatalog.toEnglish(input);
        return input.equals(translated) ? null : translated;
    }

    private static String readModeFile() {
        File file = new File(MODE_FILE);
        if (!file.isFile() || file.length() <= 0L || file.length() > 32L) return MODE_AUTO;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = in.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            in.close();
            return new String(data, 0, offset, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return MODE_AUTO;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
