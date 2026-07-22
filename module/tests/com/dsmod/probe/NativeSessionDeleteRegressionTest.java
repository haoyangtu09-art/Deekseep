package com.dsmod.probe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Verifies that batch deletion uses DeepSeek's h61(tp) event and hides stale native rows. */
public final class NativeSessionDeleteRegressionTest {
    public static final class EventSink {
        Object last;

        public Object g(Object event) {
            last = event;
            return null;
        }
    }

    private static void setMainField(String name, Object value) throws Exception {
        Field field = Main.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String sid = "11111111-2222-3333-4444-555555555555";
        Object session = Class.forName("tp").getConstructor(String.class).newInstance(sid);
        List<Object> sessions = new ArrayList<>();
        sessions.add(session);
        EventSink sink = new EventSink();

        setMainField("NATIVE_SESSION_LIST", sessions);
        setMainField("NATIVE_SESSION_EVENTS", sink);
        setMainField("LOCAL_SESSION_IDS", new HashSet<String>());

        require(Main.requestNativeSessionDelete(sid), "native deletion was not dispatched");
        require(sink.last != null && "h61".equals(sink.last.getClass().getName()),
                "expected the real h61-style event");
        Field wrapped = sink.last.getClass().getDeclaredField("a");
        wrapped.setAccessible(true);
        require(wrapped.get(sink.last) == session, "h61 did not wrap the selected tp");
        require(sessions.isEmpty(), "stale native session was not removed optimistically");
        require(Main.nativeSessionDirectory().isEmpty(),
                "deleted session was re-exposed by the editor directory");

        System.out.println("PASS: native h61 deletion bridge and stale-row suppression");
    }
}
