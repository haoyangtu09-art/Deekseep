package com.dsmod.probe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class GoogleLoginUnlockRegressionTest {
    private static final class LoginOption {}

    public static void main(String[] args) {
        addsGoogleWithoutDroppingDomesticOptions();
        doesNotDuplicateNativeGoogleOption();
        addsWechatAndMobileToInternationalOptions();
        fillsOnlyMissingRegionalOptionWithoutDuplicates();
        preservesDomesticOneTapOrder();
        leavesStartupAndUnrelatedListsUntouched();
        System.out.println("GoogleLoginUnlockRegressionTest OK");
    }

    private static void addsGoogleWithoutDroppingDomesticOptions() {
        LoginOption google = new LoginOption();
        LoginOption oneTap = new LoginOption();
        LoginOption wechat = new LoginOption();
        LoginOption password = new LoginOption();
        List<LoginOption> domestic = Collections.unmodifiableList(
                Arrays.asList(oneTap, wechat, password));

        List<?> unlocked = GoogleLoginUnlock.ensureGoogleFirst(
                domestic, google, LoginOption.class);
        check(unlocked != domestic, "immutable host list was not copied");
        check(unlocked.size() == 4, "wrong unlocked option count");
        check(unlocked.get(0) == google, "Google is not the first native option");
        check(unlocked.get(1) == oneTap && unlocked.get(2) == wechat
                && unlocked.get(3) == password, "domestic options changed order");
    }

    private static void doesNotDuplicateNativeGoogleOption() {
        LoginOption google = new LoginOption();
        List<LoginOption> international = Arrays.asList(google, new LoginOption());
        List<?> result = GoogleLoginUnlock.ensureGoogleFirst(
                international, google, LoginOption.class);
        check(result == international, "existing Google option was copied or duplicated");
    }

    private static void addsWechatAndMobileToInternationalOptions() {
        LoginOption google = new LoginOption();
        LoginOption wechat = new LoginOption();
        LoginOption mobile = new LoginOption();
        LoginOption password = new LoginOption();
        LoginOption register = new LoginOption();
        List<LoginOption> international = Collections.unmodifiableList(
                Arrays.asList(google, password, register));

        List<?> unlocked = GoogleLoginUnlock.ensureWechatAndMobile(
                international, google, wechat, mobile, LoginOption.class);
        check(unlocked != international, "immutable international list was not copied");
        check(unlocked.equals(Arrays.asList(google, wechat, mobile, password, register)),
                "regional login options have the wrong order");
    }

    private static void fillsOnlyMissingRegionalOptionWithoutDuplicates() {
        LoginOption google = new LoginOption();
        LoginOption wechat = new LoginOption();
        LoginOption mobile = new LoginOption();
        LoginOption password = new LoginOption();
        List<LoginOption> withWechat = Arrays.asList(google, wechat, password);
        List<?> unlocked = GoogleLoginUnlock.ensureWechatAndMobile(
                withWechat, google, wechat, mobile, LoginOption.class);
        check(unlocked.equals(Arrays.asList(google, wechat, mobile, password)),
                "missing mobile option was not inserted after WeChat");

        List<LoginOption> complete = Arrays.asList(google, wechat, mobile, password);
        check(GoogleLoginUnlock.ensureWechatAndMobile(
                complete, google, wechat, mobile, LoginOption.class) == complete,
                "complete regional list was copied or duplicated");
    }

    private static void preservesDomesticOneTapOrder() {
        LoginOption google = new LoginOption();
        LoginOption oneTap = new LoginOption();
        LoginOption wechat = new LoginOption();
        LoginOption mobile = new LoginOption();
        LoginOption password = new LoginOption();
        List<LoginOption> domestic = Arrays.asList(oneTap, wechat, password);
        List<?> unlocked = GoogleLoginUnlock.ensureWechatAndMobile(
                domestic, google, wechat, mobile, LoginOption.class);
        check(unlocked.equals(Arrays.asList(oneTap, wechat, mobile, password)),
                "domestic one-tap order changed");
    }

    private static void leavesStartupAndUnrelatedListsUntouched() {
        LoginOption google = new LoginOption();
        List<LoginOption> empty = new ArrayList<LoginOption>();
        check(GoogleLoginUnlock.ensureGoogleFirst(empty, google, LoginOption.class) == empty,
                "startup placeholder was changed");

        List<Object> unrelated = Arrays.<Object>asList(new LoginOption(), "not a login option");
        check(GoogleLoginUnlock.ensureGoogleFirst(unrelated, google, LoginOption.class) == unrelated,
                "unrelated list was changed");
        check(GoogleLoginUnlock.ensureWechatAndMobile(unrelated, google,
                new LoginOption(), new LoginOption(), LoginOption.class) == unrelated,
                "unrelated regional list was changed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
