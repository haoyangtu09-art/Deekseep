package com.dsmod.probe;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.TimeZone;

public final class AccountServerValidationRegressionTest {

    public static void main(String[] args) throws Exception {
        usesTheInstalledAppUserAgentShape();
        usesTheHostBearerAuthenticationChain();
        acceptsOnlyBothSuccessfulBusinessLayers();
        rejectsMismatchedAccountId();
        rejectsExpiredAndMalformedResponses();
        classifiesOnlyRateLimitsAsRetryable();
        readsTheResponseBodyExactlyOnceAndBoundsIt();
        boundsRetryAfterWithoutCreatingARequestStorm();
        System.out.println("AccountServerValidationRegressionTest OK");
    }

    private static void usesTheInstalledAppUserAgentShape() {
        String ua = AccountManager.validationUserAgent("2.2.2", 35);
        check("DeepSeek/2.2.2 Android/35".equals(ua), "exact app user agent");
        check(!ua.contains("okhttp"), "does not impersonate the HTTP library");
        check("DeepSeek/2.2.2 Android/1".equals(
                AccountManager.validationUserAgent("", 0)), "safe UA fallbacks");
    }

    private static void usesTheHostBearerAuthenticationChain() {
        Map<String, String> headers = AccountManager.validationHeaders(
                "candidate-token", "2.2.2", 35, "zh_CN", "device-id", 28800);
        check("Bearer candidate-token".equals(headers.get("Authorization")),
                "Ktor bearer auth header");
        check(!headers.containsKey("x-auth-token"),
                "telemetry auth header is never used for current-user validation");
        check("device-id".equals(headers.get("x-rangers-id")), "host device id");
        check("28800".equals(headers.get("x-client-timezone-offset")), "host timezone");
        check("https://chat.deepseek.com".equals(headers.get("Referer")), "host referer");

        Map<String, String> withoutDeviceId = AccountManager.validationHeaders(
                "candidate-token", "2.2.2", 35, "zh_CN", "", 0);
        check(!withoutDeviceId.containsKey("x-rangers-id"), "empty device id omitted");
        check(AccountManager.validationTimezoneOffsetSeconds(
                TimeZone.getTimeZone("GMT+08:00"), 0L) == 28800, "timezone seconds");
    }

    private static void acceptsOnlyBothSuccessfulBusinessLayers() {
        String body = "{\"code\":0,\"data\":{\"biz_code\":0,"
                + "\"biz_data\":{\"id\":\"account-00000001\"}}}";
        AccountManager.ServerValidation result = AccountManager.parseServerResponse(
                200, body, "account-00000001");
        check(result.valid, "valid server response");
        check(!result.retryable, "success is not retryable");

        AccountManager.ServerValidation missing = AccountManager.parseServerResponse(
                200, "{\"code\":0,\"data\":{}}", "account-00000001");
        check(!missing.valid, "missing business code is rejected");
    }

    private static void rejectsMismatchedAccountId() {
        String body = "{\"code\":0,\"data\":{\"biz_code\":0,"
                + "\"biz_data\":{\"id\":\"account-00000002\"}}}";
        AccountManager.ServerValidation result = AccountManager.parseServerResponse(
                200, body, "account-00000001");
        check(!result.valid, "mismatched id");
        check(result.error.contains("不一致"), "mismatched id explanation");
    }

    private static void rejectsExpiredAndMalformedResponses() {
        AccountManager.ServerValidation expired = AccountManager.parseServerResponse(
                200, "{\"code\":40002,\"data\":null}", "account-00000001");
        check(!expired.valid && !expired.retryable, "expired credential");
        check(expired.error.contains("40002"), "expired credential code retained");

        AccountManager.ServerValidation malformed = AccountManager.parseServerResponse(
                200, "not-json", "account-00000001");
        check(!malformed.valid && !malformed.retryable, "malformed response");
    }

    private static void classifiesOnlyRateLimitsAsRetryable() {
        AccountManager.ServerValidation limited = AccountManager.parseServerResponse(
                429, "", "account-00000001");
        check(!limited.valid && limited.retryable, "HTTP 429 retryable");

        AccountManager.ServerValidation forbidden = AccountManager.parseServerResponse(
                403, "", "account-00000001");
        check(!forbidden.valid && !forbidden.retryable, "HTTP 403 not retryable");
    }

    private static void readsTheResponseBodyExactlyOnceAndBoundsIt() throws Exception {
        String json = "{\"code\":0}";
        String read = AccountManager.readBoundedUtf8(
                new ByteArrayInputStream(json.getBytes("UTF-8")), 1024);
        check(json.equals(read), "response bytes are not duplicated");

        boolean failed = false;
        try {
            AccountManager.readBoundedUtf8(
                    new ByteArrayInputStream("12345".getBytes("UTF-8")), 4);
        } catch (Exception expected) {
            failed = true;
        }
        check(failed, "oversized response rejected");
    }

    private static void boundsRetryAfterWithoutCreatingARequestStorm() {
        check(AccountManager.retryDelayMillis(null) == 2500L, "default retry delay");
        check(AccountManager.retryDelayMillis("0") == 1500L, "minimum retry delay");
        check(AccountManager.retryDelayMillis("5") == 5000L, "server retry delay");
        check(AccountManager.retryDelayMillis("11") == -1L, "long retry deferred to user");
        check(AccountManager.retryDelayMillis("not-a-delay") == -1L,
                "unparseable retry deferred to user");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
