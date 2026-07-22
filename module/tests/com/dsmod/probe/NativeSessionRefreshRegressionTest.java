package com.dsmod.probe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Verifies that a delayed server directory refresh cannot evict an editor-created session. */
public final class NativeSessionRefreshRegressionTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object session(String sid, double updatedAt) throws Exception {
        return Class.forName("tp")
                .getConstructor(String.class, double.class, boolean.class)
                .newInstance(sid, updatedAt, false);
    }

    public static void main(String[] args) throws Exception {
        String localSid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String cloudSid = "11111111-aaaa-bbbb-cccc-222222222222";
        Object local = session(localSid, 200d);
        Object cloud = session(cloudSid, 100d);
        List<Object> canonicalState = new ArrayList<>();
        canonicalState.add(local);
        canonicalState.add(cloud);
        HashSet<String> localIds = new HashSet<>();
        localIds.add(localSid);

        require(Main.preserveEditorLocalNativeSessions(canonicalState, localIds) == 0,
                "initial local tp should only be captured");

        // Model ed0.h replacing its runtime directory with the server response.
        canonicalState.remove(local);
        Object newlySyncedCloud = session(
                "33333333-aaaa-bbbb-cccc-444444444444", 300d);
        canonicalState.add(newlySyncedCloud);

        require(Main.preserveEditorLocalNativeSessions(canonicalState, localIds) == 1,
                "editor-local tp was not restored into canonical state");
        require(canonicalState.contains(local),
                "restored state did not retain the original hydrated tp object");
        require(canonicalState.contains(cloud) && canonicalState.contains(newlySyncedCloud),
                "server sessions were damaged while restoring the local union");
        require(canonicalState.get(0) == newlySyncedCloud
                        && canonicalState.get(1) == local,
                "native pinned/time ordering was not retained");

        System.out.println("PASS: delayed native refresh preserves editor-local sessions");
    }
}
