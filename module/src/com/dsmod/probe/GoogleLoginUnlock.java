package com.dsmod.probe;

import java.util.ArrayList;
import java.util.List;

/** Pure list transformation used by the host hook and by the JVM regression test. */
final class GoogleLoginUnlock {
    private GoogleLoginUnlock() {}

    /**
     * Adds the host's own Google login option to a populated login-method list.
     * Empty lists are startup placeholders and are deliberately left untouched.
     */
    static List<?> ensureGoogleFirst(Object raw, Object googleOption, Class<?> optionType) {
        if (!(raw instanceof List) || googleOption == null || optionType == null) {
            return raw instanceof List ? (List<?>) raw : null;
        }
        List<?> source = (List<?>) raw;
        if (source.isEmpty()) return source;

        for (Object item : source) {
            if (item == googleOption || googleOption.equals(item)) return source;
            if (item != null && !optionType.isInstance(item)) return source;
        }

        ArrayList<Object> unlocked = new ArrayList<Object>(source.size() + 1);
        unlocked.add(googleOption);
        unlocked.addAll(source);
        return unlocked;
    }

    /**
     * Adds the host's native WeChat and SMS/mobile-number options to an already populated
     * login-method list.  When Google is present they are placed immediately after it; otherwise
     * an existing WeChat item is used as the anchor so domestic one-tap login keeps its position.
     */
    static List<?> ensureWechatAndMobile(Object raw, Object googleOption,
                                         Object wechatOption, Object mobileOption,
                                         Class<?> optionType) {
        if (!(raw instanceof List) || wechatOption == null || mobileOption == null
                || optionType == null) {
            return raw instanceof List ? (List<?>) raw : null;
        }
        List<?> source = (List<?>) raw;
        if (source.isEmpty()) return source;

        int googleIndex = -1;
        int wechatIndex = -1;
        boolean hasMobile = false;
        for (int i = 0; i < source.size(); i++) {
            Object item = source.get(i);
            if (item != null && !optionType.isInstance(item)) return source;
            if (same(item, googleOption)) googleIndex = i;
            if (same(item, wechatOption)) wechatIndex = i;
            if (same(item, mobileOption)) hasMobile = true;
        }
        if (wechatIndex >= 0 && hasMobile) return source;

        ArrayList<Object> unlocked = new ArrayList<Object>(source);
        int insertAt = googleIndex >= 0 ? googleIndex + 1 : 0;
        if (wechatIndex < 0) {
            unlocked.add(insertAt, wechatOption);
            wechatIndex = insertAt;
        } else if (wechatIndex >= insertAt) {
            insertAt = wechatIndex;
        }
        if (!hasMobile) unlocked.add(wechatIndex + 1, mobileOption);
        return unlocked;
    }

    private static boolean same(Object left, Object right) {
        return left == right || (left != null && left.equals(right));
    }
}
