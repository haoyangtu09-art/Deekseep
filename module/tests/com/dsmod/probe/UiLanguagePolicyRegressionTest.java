package com.dsmod.probe;

/** Regression coverage for host-locale fallback and representative English copy. */
public final class UiLanguagePolicyRegressionTest {
    public static void main(String[] args) {
        check(UiLanguage.isChineseLanguage("zh"), "zh must select Chinese");
        check(UiLanguage.isChineseLanguage("zh-Hans-CN"), "zh-Hans must select Chinese");
        check(UiLanguage.isChineseLanguage("ZH_tw"), "zh_TW must select Chinese");
        check(!UiLanguage.isChineseLanguage("en"), "English must fall back to English");
        check(!UiLanguage.isChineseLanguage("ja"), "non-Chinese host locales must use English");
        check(!UiLanguage.isChineseLanguage(null), "missing host locale must use English");
        check("zh".equals(UiLanguage.languageFromTag("zh_Hans_CN")),
                "MMKV simplified-Chinese tag parsing failed");
        check("en".equals(UiLanguage.languageFromTag("en-US")),
                "MMKV English tag parsing failed");
        check("ja".equals(UiLanguage.languageFromTag("ja")),
                "MMKV non-Chinese tag parsing failed");

        check(UiLanguage.effectiveChinese(UiLanguage.MODE_AUTO, "zh-CN"),
                "automatic Chinese detection failed");
        check(!UiLanguage.effectiveChinese(UiLanguage.MODE_AUTO, "fr"),
                "automatic non-Chinese fallback failed");
        check(UiLanguage.effectiveChinese(UiLanguage.MODE_CHINESE, "en"),
                "manual Chinese override failed");
        check(!UiLanguage.effectiveChinese(UiLanguage.MODE_ENGLISH, "zh"),
                "manual English override failed");
        check(!UiLanguage.effectiveChinese("unexpected", "ko"),
                "unknown mode must normalize to automatic English fallback");

        assertEnglish("main help", UiLanguageCatalog.toEnglish(
                "包含最新功能说明与常见问题。点一下条目展开；问题条目下方均给出解决办法。"));
        assertEnglish("experimental disclaimer", UiLanguageCatalog.toEnglish(
                "功能可能随时失败、产生不完整结果或导致数据丢失。继续表示你已理解上述风险并自行承担后果。"));
        assertEnglish("account import status", UiLanguageCatalog.toEnglish(
                "已验证并加入 3 个账号。当前登录账号未改变，可在列表中随时切换。"));
        assertEnglish("editor status", UiLanguageCatalog.toEnglish(
                "这是一个空白对话\n请用底部按钮添加用户消息或 AI 回复"));
        assertEnglish("API status", UiLanguageCatalog.toEnglish(
                "后台运行校验未通过，监听未启动"));

        System.out.println("UiLanguage policy regression tests passed");
    }

    private static void assertEnglish(String label, String value) {
        check(value != null && value.length() > 0, label + " translation is empty");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '\u3400' && ch <= '\u9fff') {
                throw new AssertionError(label + " still contains CJK text: " + value);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
