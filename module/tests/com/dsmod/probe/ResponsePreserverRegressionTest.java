package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Models a process restart whose history response contains the server's refusal template. */
public final class ResponsePreserverRegressionTest {
    static final class Session {
        String a;
        Session(String sid) { a = sid; }
    }

    static final class Response {
        Object a;
        List<Object> b;
        Response(Object session, Object... messages) {
            a = session;
            b = new ArrayList<Object>(Arrays.asList(messages));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Object message(int id, String status, String quasi, String fragments)
            throws Exception {
        Constructor<?> constructor = Class.forName("kv").getConstructor(int.class,
                String.class, String.class, String.class, String.class);
        return constructor.newInstance(id, "ASSISTANT", status, quasi, fragments);
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getField(name);
        return field.get(target);
    }

    private static String fragments(String type, String content) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("id", 1).put("type", type).put("content", content)).toString();
    }

    public static void main(String[] args) throws Exception {
        File directory = new File("build/thinking-test/response-store-" + System.nanoTime());
        require(directory.mkdirs(), "could not create isolated response store");
        System.setProperty("deekseep.response_preserver_dir", directory.getAbsolutePath());

        String sid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        String originalFragments = fragments("RESPONSE", "the complete original answer");
        String filteredFragments = fragments("TEMPLATE_RESPONSE", "这个问题，我暂时无法回答了");
        Object streamingOriginal = message(2, "STREAMING", null, originalFragments);
        Object original = message(2, "FINISHED", null, originalFragments);
        Object filtered = message(2, "CONTENT_FILTER", "CONTENT_FILTER", filteredFragments);

        require(ResponsePreserver.saveHostMessage(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, streamingOriginal),
                "streaming live original was not saved");
        require(ResponsePreserver.saveHostMessage(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, original),
                "finished live original did not supersede its equal-length streaming snapshot");
        Object restored = ResponsePreserver.restoreHostMessage(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, filtered);
        require(restored != null, "filtered cold-start message was not restored");
        require("FINISHED".equals(call(restored, "D")), "restored status is still filtered");
        require(originalFragments.equals(field(call(restored, "O"), "l")),
                "restored response body differs from the exact saved kv");

        // pw0 response path: replace the filtered list entry before rendering/snapshotting.
        Response response = new Response(new Session(sid), filtered);
        require(ResponsePreserver.restoreHistoryResponse(
                ResponsePreserverRegressionTest.class.getClassLoader(), response) == 1,
                "online history response was not repaired");
        require("FINISHED".equals(call(response.b.get(0), "D")),
                "pw0 still contains the filter template");

        // gm8/fm8 path: repair the exact row object before WCDB sees it.
        Object filteredRow = call(filtered, "O");
        List<Object> rows = new ArrayList<Object>();
        rows.add(filteredRow);
        require(ResponsePreserver.restoreRepositoryRows(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, rows) == 1,
                "repository row was not repaired");
        require("FINISHED".equals(field(filteredRow, "e")),
                "repository status is still CONTENT_FILTER");
        require(originalFragments.equals(field(filteredRow, "l")),
                "repository fragments are still the refusal template");

        // A normal server update/refusal is never replaced merely because the message ID matches.
        Object normal = message(2, "FINISHED", null, fragments("RESPONSE", "a normal update"));
        require(ResponsePreserver.restoreHostMessage(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, normal) == null,
                "ordinary server message was incorrectly replaced");

        ResponsePreserver.forgetSession(sid);
        require(ResponsePreserver.restoreHostMessage(
                ResponsePreserverRegressionTest.class.getClassLoader(), sid, filtered) == null,
                "explicit session deletion left a preserved response behind");
        File[] leftovers = directory.listFiles();
        require(leftovers != null && leftovers.length == 0, "response store was not cleaned");
        require(directory.delete(), "isolated response store directory was not removed");
        System.clearProperty("deekseep.response_preserver_dir");

        System.out.println("PASS: filtered responses survive cold history sync without broad overrides");
    }
}
