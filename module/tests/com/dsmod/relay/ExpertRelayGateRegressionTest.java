package com.dsmod.relay;

public final class ExpertRelayGateRegressionTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(ExpertRelayGate.matches("expert", null, true),
                "first-turn explicit expert request was rejected");
        check(ExpertRelayGate.matches(null, "expert", true),
                "later-turn request with captured expert model was rejected");
        check(!ExpertRelayGate.matches(null, "default", true),
                "later-turn default request was treated as expert");
        check(!ExpertRelayGate.matches("vision", "expert", true),
                "captured model overrode an explicit non-expert request");
        check(!ExpertRelayGate.matches(null, null, true),
                "request without reliable model context was treated as expert");
        check(!ExpertRelayGate.matches("expert", null, false),
                "expert request without files entered the image relay");
        System.out.println("PASS: expert relay resolves explicit and later-turn models safely");
    }
}
