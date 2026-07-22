package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public final class LocalApiGatewayProtocolRegressionTest {
    private static Method handleChat;
    private static Method handleResponses;
    private static Method handleAnthropic;
    private static Method handleAnthropicCountTokens;
    private static Method translateChat;
    private static Method translateResponses;
    private static Method translateAnthropic;
    private static Method modelById;
    private static Method requiresWorkspaceFileAction;
    private static Method writeAnthropicActivity;
    private static Method approximateTokens;
    private static Method lanInterfacePriority;
    private static ByteArrayOutputStream activeWire;

    public static void main(String[] args) throws Exception {
        installFakeBackend();
        handleChat = LocalApiGateway.class.getDeclaredMethod(
                "handleChat", java.io.OutputStream.class, JSONObject.class);
        handleResponses = LocalApiGateway.class.getDeclaredMethod(
                "handleResponses", java.io.OutputStream.class, JSONObject.class);
        handleAnthropic = LocalApiGateway.class.getDeclaredMethod(
                "handleAnthropicMessages", java.io.OutputStream.class, JSONObject.class);
        handleAnthropicCountTokens = LocalApiGateway.class.getDeclaredMethod(
                "handleAnthropicCountTokens", java.io.OutputStream.class, JSONObject.class);
        translateChat = LocalApiGateway.class.getDeclaredMethod(
                "translateChat", String.class, JSONObject.class);
        translateResponses = LocalApiGateway.class.getDeclaredMethod(
                "translateResponses", String.class, JSONObject.class);
        translateAnthropic = LocalApiGateway.class.getDeclaredMethod(
                "translateAnthropic", String.class, JSONObject.class, boolean.class);
        modelById = LocalApiGateway.class.getDeclaredMethod("modelById", String.class);
        requiresWorkspaceFileAction = LocalApiGateway.class.getDeclaredMethod(
                "requiresWorkspaceFileAction", LocalApiGateway.CompletionRequest.class);
        writeAnthropicActivity = LocalApiGateway.class.getDeclaredMethod(
                "writeAnthropicActivityEvent", java.io.OutputStream.class, int.class);
        approximateTokens = LocalApiGateway.class.getDeclaredMethod(
                "approximateTokens", String.class);
        lanInterfacePriority = LocalApiGateway.class.getDeclaredMethod(
                "lanInterfacePriority", String.class);
        handleChat.setAccessible(true);
        handleResponses.setAccessible(true);
        handleAnthropic.setAccessible(true);
        handleAnthropicCountTokens.setAccessible(true);
        translateChat.setAccessible(true);
        translateResponses.setAccessible(true);
        translateAnthropic.setAccessible(true);
        modelById.setAccessible(true);
        requiresWorkspaceFileAction.setAccessible(true);
        writeAnthropicActivity.setAccessible(true);
        approximateTokens.setAccessible(true);
        lanInterfacePriority.setAccessible(true);

        auxiliaryToolRequestDoesNotEnterInteractiveLane();
        anthropicClientSessionScopeRotates();
        anthropicStaleUuidRotatesOnNewTranscript();
        anthropicExplicitSessionScopeWins();
        anthropicWaitsForUpstreamBeforeThinking();
        workspaceWriteIntentIsConservative();
        anthropicActivityEventIsVisibleToClients();
        anthropicActivityRestoresThinking();
        anthropicCumulativeUsageDoesNotInflateFragments();
        lanAddressPrefersReachableInterfaces();
        chatNonStreamToolCall();
        chatSseToolCall();
        responsesSseShellCall();
        responsesNamespaceCall();
        responsesPreviousIdToolLoop();
        responsesSuppressesCompletedDuplicate();
        responsesRecoversMalformedCallAfterToolOutput();
        codexCompatibilityModelAlias();
        codexResponsesContract();
        codexTransportItemsStayOpaque();
        openAiModelRetrieveAndStructuredOutput();
        anthropicNonStreamMessage();
        anthropicSseText();
        anthropicSseToolCall();
        anthropicStreamsThinkingWithTools();
        anthropicHidesTaggedToolEnvelope();
        anthropicHidesEmbeddedToolEnvelope();
        anthropicHidesNarratedToolTranscript();
        anthropicRepairsDeferredAgentAction();
        anthropicRepairsCodeInsteadOfWriting();
        anthropicRepairsSearchReadPromise();
        anthropicUsesSecondAgentRepair();
        anthropicFallsBackToSafeContinuation();
        anthropicRepairsDeferredThinkingAction();
        anthropicConvertsClaudeDisplayShorthand();
        anthropicToolResultLoop();
        anthropicAllowsRepeatedReadVerification();
        anthropicAllowsReadAfterEditVerification();
        anthropicSuppressesBashMetadataDuplicate();
        anthropicEndsStubbornDuplicateWithoutRetryError();
        anthropicThinkingBlock();
        anthropicCountTokens();
        System.out.println("LocalApiGatewayProtocolRegressionTest OK");
    }

    private static void auxiliaryToolRequestDoesNotEnterInteractiveLane() throws Exception {
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", responseFunction("Read"))), "auto", true);
        LocalApiGateway.CompletionRequest auxiliary = new LocalApiGateway.CompletionRequest(
                "aux", "deepseek-aux", "default", "base", "prompt", false, false,
                1024, plan, null, false);
        LocalApiGateway.CompletionRequest interactive = new LocalApiGateway.CompletionRequest(
                "agent", "deepseek-chat", "default", "base", "prompt", false, false,
                1024, plan, null, false);
        check(auxiliary.toolsActive() && auxiliary.auxiliary()
                        && !auxiliary.interactiveAgent(),
                "tool-bearing Claude auxiliary request would deadlock the Agent lane");
        check(auxiliary.agentic(),
                "tool-bearing Claude Task/subagent request lost serialized Agent priority");
        check(interactive.interactiveAgent(),
                "interactive Claude tool request lost Agent-lane priority");
    }

    private static void anthropicClientSessionScopeRotates() throws Exception {
        JSONObject firstBody = anthropicBody(false, false).put("metadata",
                new JSONObject().put("user_id", "user_demo_session_11111111"));
        JSONObject sameBody = anthropicBody(false, false).put("metadata",
                new JSONObject().put("user_id", "user_demo_session_11111111"));
        JSONObject newBody = anthropicBody(false, false).put("metadata",
                new JSONObject().put("user_id", "user_demo_session_22222222"));
        LocalApiGateway.CompletionRequest first = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-a", firstBody, true);
        LocalApiGateway.CompletionRequest same = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-b", sameBody, true);
        LocalApiGateway.CompletionRequest rotated = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-c", newBody, true);
        check(first.clientSessionScope != null
                        && first.clientSessionScope.equals(same.clientSessionScope),
                "stable Claude session metadata did not reuse its native branch");
        check(!first.clientSessionScope.equals(rotated.clientSessionScope),
                "/clear or /new session metadata did not rotate the native branch");
        check(!first.clientSessionScope.contains("user_demo"),
                "raw client identity leaked into persistent session state");

        JSONObject changedPrefix = anthropicBody(false, false).put("metadata",
                new JSONObject().put("user_id", "another_account_session_11111111"));
        LocalApiGateway.CompletionRequest sameSuffix = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-d", changedPrefix, true);
        check(first.clientSessionScope.equals(sameSuffix.clientSessionScope),
                "Claude metadata prefix changed a stable _session_ UUID scope");
    }

    private static void anthropicExplicitSessionScopeWins() throws Exception {
        JSONObject explicit = anthropicBody(false, false)
                .put("_deekseep_client_session_id", "header-session")
                .put("metadata", new JSONObject().put("user_id",
                        "metadata_session_ignored"));
        JSONObject expected = anthropicBody(false, false).put("metadata",
                new JSONObject().put("user_id", "header-session"));
        LocalApiGateway.CompletionRequest actualRequest = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-header", explicit, true);
        LocalApiGateway.CompletionRequest expectedRequest = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "scope-expected", expected, true);
        check(actualRequest.clientSessionScope.equals(expectedRequest.clientSessionScope),
                "explicit Claude session header did not override metadata.user_id");
    }

    private static void anthropicStaleUuidRotatesOnNewTranscript() throws Exception {
        JSONObject metadata = new JSONObject().put("user_id",
                "user_demo_session_stale-client-uuid");
        JSONObject firstBody = anthropicBody(false, false).put("metadata", metadata);
        JSONObject laterBody = anthropicBody(false, false).put("metadata", metadata)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user")
                                .put("content", "look it up"))
                        .put(new JSONObject().put("role", "assistant")
                                .put("content", "prior answer"))
                        .put(new JSONObject().put("role", "user")
                                .put("content", "continue")));
        JSONObject clearedBody = anthropicBody(false, false).put("metadata", metadata)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "brand new transcript")));
        LocalApiGateway.CompletionRequest first = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "root-a", firstBody, true);
        LocalApiGateway.CompletionRequest later = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "root-b", laterBody, true);
        LocalApiGateway.CompletionRequest cleared = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "root-c", clearedBody, true);
        check(first.clientSessionScope.equals(later.clientSessionScope),
                "later Claude turns changed the stable conversation-root scope");
        check(!first.clientSessionScope.equals(cleared.clientSessionScope),
                "a cleared transcript reused the old native branch when UUID stayed stale");
    }

    private static void anthropicWaitsForUpstreamBeforeThinking() throws Exception {
        JSONObject body = anthropicBody(true, false).put("messages",
                new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_UPSTREAM_ORDER")));
        String wire = invokeWire(handleAnthropic, body);
        int start = wire.indexOf("event: message_start");
        int thinking = wire.indexOf("\"type\":\"thinking\"", start);
        int reasoning = wire.indexOf("UPSTREAM_READY", thinking);
        check(start > 0 && thinking > start && reasoning > thinking,
                "Anthropic lifecycle did not start after the upstream-ready boundary");
    }

    private static void workspaceWriteIntentIsConservative() throws Exception {
        JSONObject write = new JSONObject().put("name", "Write")
                .put("description", "Write a file")
                .put("input_schema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("file_path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path").put("content")));
        JSONObject mutateBody = anthropicBody(false, false)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "请创建文件 calculator.js 并实现计算器")))
                .put("tools", new JSONArray().put(write));
        JSONObject snippetBody = anthropicBody(false, false)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "请只给一个计算器代码示例，不要修改文件")))
                .put("tools", new JSONArray().put(write));
        LocalApiGateway.CompletionRequest mutate = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "intent-write", mutateBody, true);
        LocalApiGateway.CompletionRequest snippet = (LocalApiGateway.CompletionRequest)
                translateAnthropic.invoke(null, "intent-snippet", snippetBody, true);
        check(Boolean.TRUE.equals(requiresWorkspaceFileAction.invoke(null, mutate)),
                "explicit workspace file creation was not recognized as a required action");
        check(Boolean.FALSE.equals(requiresWorkspaceFileAction.invoke(null, snippet)),
                "an explicit code-example request was incorrectly forced to mutate files");
    }

    private static void anthropicActivityEventIsVisibleToClients() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        writeAnthropicActivity.invoke(null, wire, 321);
        String value = wire.toString("UTF-8");
        check(value.contains("event: message_delta")
                        && value.contains("\"output_tokens\":321")
                        && value.contains("\"stop_reason\":null"),
                "queued Anthropic work did not emit a non-terminal cumulative activity event");
    }

    private static void anthropicActivityRestoresThinking() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Class<?> heartbeatType = Class.forName(
                "com.dsmod.probe.LocalApiGateway$SseHeartbeat");
        java.lang.reflect.Constructor<?> heartbeatConstructor =
                heartbeatType.getDeclaredConstructor(java.io.OutputStream.class,
                        String.class, String.class);
        heartbeatConstructor.setAccessible(true);
        Object heartbeat = heartbeatConstructor.newInstance(
                wire, LocalApiGateway.PROTOCOL_ANTHROPIC, "heartbeat-test");

        Class<?> emitterType = Class.forName(
                "com.dsmod.probe.LocalApiGateway$AnthropicStreamEmitter");
        java.lang.reflect.Constructor<?> emitterConstructor =
                emitterType.getDeclaredConstructor(java.io.OutputStream.class,
                        LocalApiGateway.CompletionRequest.class, heartbeatType);
        emitterConstructor.setAccessible(true);
        LocalApiGateway.CompletionRequest request = new LocalApiGateway.CompletionRequest(
                "heartbeat-test", "deepseek-chat", "default", "base", "prompt",
                true, false, 1024, null, null, false);
        Object emitter = emitterConstructor.newInstance(wire, request, heartbeat);
        Method attach = heartbeatType.getDeclaredMethod("attach", emitterType);
        Method beginThinking = emitterType.getDeclaredMethod("beginThinking");
        Method activity = emitterType.getDeclaredMethod("onAnthropicActivity", int.class);
        Method stop = heartbeatType.getDeclaredMethod("stop");
        attach.setAccessible(true);
        beginThinking.setAccessible(true);
        activity.setAccessible(true);
        stop.setAccessible(true);
        attach.invoke(heartbeat, emitter);
        beginThinking.invoke(emitter);
        activity.invoke(emitter, 7);
        stop.invoke(heartbeat);

        String value = wire.toString("UTF-8");
        int usage = value.indexOf("event: message_delta");
        int restored = value.indexOf("\"type\":\"thinking\"", usage);
        check(usage >= 0 && restored > usage
                        && value.contains("\"output_tokens\":7"),
                "usage heartbeat did not reopen thinking before public text began");
    }

    private static void anthropicCumulativeUsageDoesNotInflateFragments() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Class<?> heartbeatType = Class.forName(
                "com.dsmod.probe.LocalApiGateway$SseHeartbeat");
        java.lang.reflect.Constructor<?> constructor = heartbeatType.getDeclaredConstructor(
                java.io.OutputStream.class, String.class, String.class);
        constructor.setAccessible(true);
        Object heartbeat = constructor.newInstance(
                wire, LocalApiGateway.PROTOCOL_ANTHROPIC, "usage-test");
        Method add = heartbeatType.getDeclaredMethod("addAnthropicOutput", String.class);
        Method tokens = heartbeatType.getDeclaredMethod("anthropicOutputTokens");
        add.setAccessible(true);
        tokens.setAccessible(true);
        add.invoke(heartbeat, "a");
        add.invoke(heartbeat, "b");
        add.invoke(heartbeat, "c");
        add.invoke(heartbeat, "d");
        int cumulative = ((Number) tokens.invoke(heartbeat)).intValue();
        int expected = ((Number) approximateTokens.invoke(null, "abcd")).intValue();
        check(cumulative == expected && cumulative == 1,
                "fragment boundaries inflated cumulative Anthropic output tokens");
    }

    private static void lanAddressPrefersReachableInterfaces() throws Exception {
        int wlan = ((Number) lanInterfacePriority.invoke(null, "wlan0")).intValue();
        int hotspot = ((Number) lanInterfacePriority.invoke(null, "ap0")).intValue();
        int ethernet = ((Number) lanInterfacePriority.invoke(null, "eth0")).intValue();
        int vpn = ((Number) lanInterfacePriority.invoke(null, "tun0")).intValue();
        int cellular = ((Number) lanInterfacePriority.invoke(null, "rmnet_data0")).intValue();
        check(wlan < hotspot && hotspot < ethernet && vpn < 0 && cellular < 0,
                "LAN endpoint selection did not prefer Wi-Fi/hotspot/ethernet or skip VPN/mobile");
    }

    private static void installFakeBackend() throws Exception {
        Field backend = LocalApiGateway.class.getDeclaredField("backend");
        backend.setAccessible(true);
        backend.set(null, new LocalApiGateway.Backend() {
            @Override public boolean isReady() { return true; }
            @Override public String readinessDetail() { return "test-ready"; }
            @Override public LocalApiGateway.CompletionResult complete(
                    LocalApiGateway.CompletionRequest request,
                    LocalApiGateway.DeltaSink sink) throws Exception {
                if (request.prompt.contains("ANTHROPIC_INCREMENTAL_STREAM")) {
                    check(sink != null, "Anthropic tool request discarded its live delta sink");
                    check(sink.onReasoning("think-"), "thinking sink disconnected early");
                    check(sink.onReasoning("live"), "thinking sink disconnected early");
                    check(sink.onText("LIVE_"), "text sink disconnected early");
                    check(activeWire != null
                                    && activeWire.toString("UTF-8").contains("LIVE_"),
                            "short native text was held until completion instead of streamed");
                    check(sink.onText("TEXT"), "text sink disconnected early");
                    return new LocalApiGateway.CompletionResult(
                            "LIVE_TEXT", "think-live", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_UPSTREAM_ORDER")) {
                    check(sink != null, "upstream-order request discarded its delta sink");
                    check(activeWire != null
                                    && !activeWire.toString("UTF-8").contains(
                                            "event: message_start"),
                            "Anthropic message_start was sent before upstream processing");
                    sink.onUpstreamStarted();
                    check(activeWire.toString("UTF-8").contains("event: message_start"),
                            "upstream-ready did not start the Anthropic lifecycle");
                    check(sink.onReasoning("UPSTREAM_READY"),
                            "upstream-order reasoning sink disconnected");
                    return new LocalApiGateway.CompletionResult(
                            "ORDER_DONE", "UPSTREAM_READY", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_TAGGED_TOOL_STREAM")) {
                    check(sink != null, "tagged tool request discarded its live delta sink");
                    String tagged = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>functions.Write:0"
                            + "<｜tool▁call▁argument▁begin｜>{\"file_path\":\"probe.txt\","
                            + "\"content\":\"OK\"}<｜tool▁call▁end｜><｜tool▁calls▁end｜>";
                    check(sink.onReasoning("tool-plan"), "tool reasoning sink disconnected");
                    check(sink.onText(tagged.substring(0, tagged.length() / 2)),
                            "tagged tool sink disconnected");
                    check(sink.onText(tagged.substring(tagged.length() / 2)),
                            "tagged tool sink disconnected");
                    return new LocalApiGateway.CompletionResult(tagged, "tool-plan", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_EMBEDDED_TOOL_STREAM")) {
                    check(sink != null, "embedded tool request discarded its live delta sink");
                    StringBuilder padding = new StringBuilder();
                    for (int i = 0; i < 360; i++) padding.append('X');
                    String envelope = "{\"ekseep_tool_calls\":[{\"name\":\"Write\","
                            + "\"arguments\":{\"file_path\":\"embedded.txt\","
                            + "\"content\":\"" + padding + "\"}}]}";
                    String reasoning = "Reasoning remains visible. " + envelope;
                    String text = "Visible plan remains. " + envelope;
                    check(sink.onReasoning(reasoning.substring(0, 31)),
                            "embedded reasoning sink disconnected");
                    check(sink.onReasoning(reasoning.substring(31)),
                            "embedded reasoning sink disconnected");
                    check(sink.onText(text.substring(0, 37)),
                            "embedded text sink disconnected");
                    check(sink.onText(text.substring(37)),
                            "embedded text sink disconnected");
                    return new LocalApiGateway.CompletionResult(text, reasoning, "stop");
                }
                if (request.prompt.contains("ANTHROPIC_NARRATED_TOOL_STREAM")) {
                    check(sink != null, "narrated tool request discarded its live delta sink");
                    String narrated = "[assistant]\nRequested tool Read (call_id=fake) "
                            + "with arguments:\n{\"file_path\":\"input.txt\"}\n\n"
                            + "[tool]\nTool result:\nfabricated";
                    check(sink.onText("["), "narrated prefix sink disconnected");
                    check(sink.onText(narrated.substring(1, 29)),
                            "narrated name sink disconnected");
                    check(sink.onText(narrated.substring(29)),
                            "narrated arguments sink disconnected");
                    return new LocalApiGateway.CompletionResult(narrated, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_DEFERRED_ACTION")) {
                    if (request.prompt.contains("[AGENT ACTION REQUIRED]")) {
                        String call = "<tool>{\"name\":\"Read\",\"arguments\":"
                                + "{\"file_path\":\"deferred.txt\"}}</tool>";
                        if (sink != null) check(sink.onText(call),
                                "deferred-action repair sink disconnected");
                        return new LocalApiGateway.CompletionResult(call, "repair-think", "stop");
                    }
                    String narration = "我将读取文件，然后继续完成任务。";
                    if (sink != null) check(sink.onReasoning("plan"),
                            "deferred reasoning sink disconnected");
                    if (sink != null) check(sink.onText(narration),
                            "deferred narration sink disconnected");
                    return new LocalApiGateway.CompletionResult(narration, "plan", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_CODE_INSTEAD_OF_WRITE")) {
                    if (request.prompt.contains("[AGENT ACTION REQUIRED]")) {
                        String call = "<tool>{\"name\":\"Write\",\"arguments\":"
                                + "{\"file_path\":\"created-by-tool.txt\","
                                + "\"content\":\"TOOL_WROTE_THIS\"}}</tool>";
                        if (sink != null) check(sink.onText(call),
                                "code-only repair sink disconnected");
                        return new LocalApiGateway.CompletionResult(call, "", "stop");
                    }
                    String codeOnly = "```text\nTOOL_DID_NOT_WRITE_THIS\n```";
                    if (sink != null) check(sink.onText(codeOnly),
                            "code-only initial sink disconnected");
                    return new LocalApiGateway.CompletionResult(codeOnly, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_SEARCH_READ_PROMISE")) {
                    if (request.prompt.contains("[AGENT ACTION REQUIRED]")) {
                        String call = "<tool>{\"name\":\"Read\",\"arguments\":"
                                + "{\"file_path\":\"search-result.txt\"}}</tool>";
                        if (sink != null) check(sink.onText(call),
                                "search/read repair sink disconnected");
                        return new LocalApiGateway.CompletionResult(call, "", "stop");
                    }
                    String narration = "我来帮你搜索相关资料。我先读取它，同时进行网络搜索。";
                    if (sink != null) check(sink.onText(narration),
                            "search/read narration sink disconnected");
                    return new LocalApiGateway.CompletionResult(narration, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_SECOND_REPAIR")) {
                    if (request.prompt.contains("Repair attempt 2")) {
                        String call = "<tool>{\"name\":\"Read\",\"arguments\":"
                                + "{\"file_path\":\"second-repair.txt\"}}</tool>";
                        if (sink != null) check(sink.onText(call),
                                "second Agent repair sink disconnected");
                        return new LocalApiGateway.CompletionResult(call, "", "stop");
                    }
                    String narration = request.prompt.contains("Repair attempt 1")
                            ? "我会先继续分析，然后再读取文件。"
                            : "我先分析现状，然后读取文件。";
                    if (sink != null) check(sink.onText(narration),
                            "two-stage Agent narration sink disconnected");
                    return new LocalApiGateway.CompletionResult(narration, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_CONTINUATION_FALLBACK")) {
                    String narration = "我现在会继续检查目录，然后开始处理。";
                    if (sink != null) check(sink.onText(narration),
                            "continuation fallback narration sink disconnected");
                    return new LocalApiGateway.CompletionResult(narration, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_DEFERRED_THINK_ACTION")) {
                    if (request.prompt.contains("[AGENT ACTION REQUIRED]")) {
                        String call = "<tool>{\"name\":\"Read\",\"arguments\":"
                                + "{\"file_path\":\"deferred-thinking.txt\"}}</tool>";
                        if (sink != null) check(sink.onText(call),
                                "deferred-thinking repair sink disconnected");
                        return new LocalApiGateway.CompletionResult(
                                call, "repair-thinking", "stop");
                    }
                    String reasoning = "写入已完成。现在需要再次验证；我将用 Read 工具读取文件。";
                    if (sink != null) check(sink.onReasoning(reasoning),
                            "deferred-thinking sink disconnected");
                    return new LocalApiGateway.CompletionResult("", reasoning, "stop");
                }
                if (request.prompt.contains("ANTHROPIC_SHORTHAND_TOOL_STREAM")) {
                    check(sink != null, "shorthand tool request discarded its live delta sink");
                    String shorthand = "我来检查当前目录。\nBash({\"command\":\"pwd && ls -la\","
                            + "\"description\":\"Show current directory\"})";
                    check(sink.onText("我来检查当前目录。\nB"),
                            "shorthand prefix sink disconnected");
                    check(sink.onText(shorthand.substring("我来检查当前目录。\nB".length())),
                            "shorthand arguments sink disconnected");
                    check(sink.isSatisfied(),
                            "complete shorthand call did not stop further native generation");
                    return new LocalApiGateway.CompletionResult(shorthand, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_REPEAT_READ")) {
                    String envelope = "{\"deekseep_tool_calls\":[{\"name\":\"Read\","
                            + "\"arguments\":{\"file_path\":\"repeat.txt\"}}]}";
                    if (sink != null) check(sink.onText(envelope),
                            "repeat Read sink disconnected");
                    return new LocalApiGateway.CompletionResult(envelope, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_STUBBORN_DUPLICATE")) {
                    String envelope = "{\"deekseep_tool_calls\":[{\"name\":\"Edit\","
                            + "\"arguments\":{\"file_path\":\"repeat.txt\","
                            + "\"old_string\":\"A\",\"new_string\":\"B\"}}]}";
                    if (sink != null) check(sink.onText(envelope),
                            "stubborn duplicate sink disconnected");
                    return new LocalApiGateway.CompletionResult(envelope, "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_BASH_METADATA_DUPLICATE")) {
                    if (request.prompt.contains("[DUPLICATE TOOL CORRECTION]")) {
                        return new LocalApiGateway.CompletionResult(
                                "BASH_DUPLICATE_RECOVERED", "", "stop");
                    }
                    return new LocalApiGateway.CompletionResult(
                            "{\"deekseep_tool_calls\":[{\"name\":\"Bash\","
                                    + "\"arguments\":{\"command\":\"pwd\","
                                    + "\"description\":\"Check current directory\","
                                    + "\"timeout\":30000}}]}", "", "stop");
                }
                if (request.prompt.contains("BROKEN_REPEAT_TEST")) {
                    if (request.prompt.contains("[DUPLICATE TOOL CORRECTION]")) {
                        return new LocalApiGateway.CompletionResult(
                                "MALFORMED_REPEAT_RECOVERED", "", "stop");
                    }
                    return new LocalApiGateway.CompletionResult(
                            "{\"deekseep_tool_calls\":[{\"name\":\"lookup\","
                                    + "\"arguments\":{\"query\":\"same\"}", "", "stop");
                }
                if (request.prompt.contains("DUPLICATE_AGENT_TEST")) {
                    if (request.prompt.contains("[DUPLICATE TOOL CORRECTION]")) {
                        return new LocalApiGateway.CompletionResult(
                                "DUPLICATE_RECOVERED", "", "stop");
                    }
                    return new LocalApiGateway.CompletionResult(
                            "{\"deekseep_tool_calls\":[{\"name\":\"lookup\","
                                    + "\"arguments\":{\"query\":\"same\",\"limit\":2}}]}",
                            "", "stop");
                }
                if (request.prompt.contains("ANTHROPIC_THINK")) {
                    return new LocalApiGateway.CompletionResult(
                            "THINK_DONE", "hidden plan", "stop");
                }
                if (request.prompt.contains("CODEX_CUSTOM_PATCH")) {
                    return new LocalApiGateway.CompletionResult(
                            "<tool>{\"name\":\"apply_patch\",\"arguments\":{\"input\":"
                                    + "\"*** Begin Patch\\n*** Add File: codex-probe.txt\\n"
                                    + "+created by tool\\n*** End Patch\"}}</tool>",
                            "", "stop");
                }
                if (request.prompt.contains("Tool result for")) {
                    if (request.previousResponseId != null) check(!request.toolsActive(),
                            "previous_response_id silently inherited a completed tool");
                    return new LocalApiGateway.CompletionResult("TOOL_LOOP_DONE", "", "stop");
                }
                if (request.toolPlan != null && request.toolPlan.find("shell", null) != null) {
                    return new LocalApiGateway.CompletionResult(
                            "<tool>{\"name\":\"shell\","
                                    + "\"arguments\":{\"commands\":[\"pwd\"]}}</tool>",
                            "", "stop");
                }
                if (request.prompt.contains("multi_agent_v1")) {
                    return new LocalApiGateway.CompletionResult(
                            "<tool>{\"name\":\"multi_agent_v1.spawn_agent\","
                                    + "\"arguments\":{\"query\":\"agent\"}}</tool>",
                            "", "stop");
                }
                if (request.toolsActive()) {
                    return new LocalApiGateway.CompletionResult(
                            "<tool>{\"name\":\"lookup\","
                                    + "\"arguments\":{\"query\":\"agent\"}}</tool>",
                            "", "stop");
                }
                if (sink != null) {
                    sink.onText("FAKE_");
                    sink.onText("STREAM");
                }
                return new LocalApiGateway.CompletionResult("FAKE_STREAM", "", "stop");
            }
        });
    }

    private static void chatNonStreamToolCall() throws Exception {
        JSONObject body = chatBody(false);
        JSONObject response = invokeJson(handleChat, body);
        JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
        check("tool_calls".equals(choice.getString("finish_reason")),
                "non-stream Chat finish_reason is not tool_calls");
        JSONObject message = choice.getJSONObject("message");
        check(message.isNull("content"), "tool-call Chat content is not null");
        JSONObject call = message.getJSONArray("tool_calls").getJSONObject(0);
        check(call.getString("id").startsWith("call_"), "Chat call id is invalid");
        check("lookup".equals(call.getJSONObject("function").getString("name")),
                "Chat function name is wrong");
    }

    private static void chatSseToolCall() throws Exception {
        String wire = invokeWire(handleChat, chatBody(true));
        check(wire.startsWith("HTTP/1.1 200 OK\r\n"), "Chat SSE status/header is wrong");
        check(wire.contains("text/event-stream"), "Chat SSE content type is missing");
        check(wire.contains("\"tool_calls\""), "Chat SSE tool delta is missing");
        check(wire.contains("\"finish_reason\":\"tool_calls\""),
                "Chat SSE tool finish is missing");
        check(wire.contains("data: [DONE]"), "Chat SSE terminator is missing");
    }

    private static void responsesSseShellCall() throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat").put("stream", true)
                .put("input", "show cwd")
                .put("tools", new JSONArray().put(new JSONObject().put("type", "shell")))
                .put("tool_choice", "required");
        String wire = invokeWire(handleResponses, body);
        check(wire.contains("event: response.created"), "Responses created event is missing");
        check(wire.contains("event: response.in_progress"),
                "Responses in_progress event is missing");
        check(wire.contains("\"type\":\"shell_call\""),
                "Responses shell_call item is missing");
        check(wire.contains("\"commands\":[\"pwd\"]"),
                "Responses shell action is wrong");
        check(wire.contains("event: response.output_item.done"),
                "Responses output_item.done is missing");
        check(wire.contains("event: response.completed"),
                "Responses completed event is missing");
    }

    private static void openAiModelRetrieveAndStructuredOutput() throws Exception {
        JSONObject model = (JSONObject) modelById.invoke(null, "gpt-5.4");
        check(model != null && "model".equals(model.getString("object"))
                        && model.optBoolean("compatibility_alias", false),
                "OpenAI model retrieve did not return the compatibility model object");
        check(modelById.invoke(null, "missing-model") == null,
                "unknown OpenAI model unexpectedly resolved");

        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("answer",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("answer"));
        JSONObject chat = new JSONObject().put("model", "deepseek-chat")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "return data")))
                .put("response_format", new JSONObject().put("type", "json_schema")
                        .put("json_schema", new JSONObject().put("name", "answer")
                                .put("strict", true).put("schema", schema)));
        LocalApiGateway.CompletionRequest chatRequest =
                (LocalApiGateway.CompletionRequest) translateChat.invoke(null, "json-chat", chat);
        check(chatRequest.prompt.contains("[OUTPUT FORMAT]")
                        && chatRequest.prompt.contains("\"answer\""),
                "Chat json_schema was not translated into a model instruction");

        JSONObject responses = new JSONObject().put("model", "deepseek-chat")
                .put("input", "return data")
                .put("text", new JSONObject().put("format", new JSONObject()
                        .put("type", "json_schema").put("name", "answer")
                        .put("strict", true).put("schema", schema)));
        LocalApiGateway.CompletionRequest responsesRequest =
                (LocalApiGateway.CompletionRequest) translateResponses.invoke(
                        null, "json-responses", responses);
        check(responsesRequest.outputTextFormat != null
                        && "json_schema".equals(responsesRequest.outputTextFormat
                                .getString("type")),
                "Responses text.format was not retained on the response request");
        JSONObject response = invokeJson(handleResponses, responses);
        check("json_schema".equals(response.getJSONObject("text")
                        .getJSONObject("format").getString("type")),
                "Responses object did not echo the normalized text.format");
    }

    private static void responsesNamespaceCall() throws Exception {
        JSONObject namespace = new JSONObject().put("type", "namespace")
                .put("name", "multi_agent_v1").put("description", "agents")
                .put("tools", new JSONArray().put(responseFunction("spawn_agent")));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("input", "delegate").put("tools", new JSONArray().put(namespace)
                        .put(new JSONObject().put("type", "web_search")
                                .put("external_web_access", false)))
                .put("tool_choice", "required");
        JSONObject response = invokeJson(handleResponses, body);
        JSONObject call = response.getJSONArray("output").getJSONObject(0);
        check("function_call".equals(call.getString("type")),
                "namespace did not produce a function call");
        check("multi_agent_v1".equals(call.getString("namespace")),
                "namespace was not returned on the function call");
        check("spawn_agent".equals(call.getString("name")),
                "nested namespace tool name changed");
    }

    private static void responsesPreviousIdToolLoop() throws Exception {
        JSONObject firstBody = new JSONObject().put("model", "deepseek-chat")
                .put("input", "look up agent")
                .put("tools", new JSONArray().put(responseFunction("lookup")))
                .put("tool_choice", "required");
        JSONObject first = invokeJson(handleResponses, firstBody);
        String responseId = first.getString("id");
        JSONObject tool = first.getJSONArray("output").getJSONObject(0);
        check("function_call".equals(tool.getString("type")),
                "Responses non-stream function call is missing");

        JSONObject secondBody = new JSONObject().put("model", "deepseek-chat")
                .put("previous_response_id", responseId)
                .put("input", new JSONArray().put(new JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", tool.getString("call_id"))
                        .put("output", "agent result")));
        JSONObject second = invokeJson(handleResponses, secondBody);
        JSONObject message = second.getJSONArray("output").getJSONObject(0);
        String text = message.getJSONArray("content").getJSONObject(0).getString("text");
        check("TOOL_LOOP_DONE".equals(text), "previous_response_id tool result was not continued");
        check(responseId.equals(second.getString("previous_response_id")),
                "previous_response_id was not echoed");
    }

    private static void responsesSuppressesCompletedDuplicate() throws Exception {
        JSONArray input = new JSONArray()
                .put(new JSONObject().put("type", "message").put("role", "user")
                        .put("content", "DUPLICATE_AGENT_TEST"))
                .put(new JSONObject().put("type", "function_call")
                        .put("call_id", "call_already_done").put("name", "lookup")
                        .put("arguments", "{\"limit\":2,\"query\":\"same\"}"))
                .put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", "call_already_done").put("output", "success"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("input", input)
                .put("tools", new JSONArray().put(responseFunction("lookup")))
                .put("tool_choice", "auto");
        JSONObject response = invokeJson(handleResponses, body);
        JSONObject message = response.getJSONArray("output").getJSONObject(0);
        String text = message.getJSONArray("content").getJSONObject(0).getString("text");
        check("DUPLICATE_RECOVERED".equals(text),
                "an already-successful identical tool call escaped duplicate recovery");
    }

    private static void responsesRecoversMalformedCallAfterToolOutput() throws Exception {
        JSONArray input = new JSONArray()
                .put(new JSONObject().put("type", "message").put("role", "user")
                        .put("content", "BROKEN_REPEAT_TEST"))
                .put(new JSONObject().put("type", "function_call")
                        .put("call_id", "call_broken_done").put("name", "lookup")
                        .put("arguments", "{\"query\":\"same\"}"))
                .put(new JSONObject().put("type", "function_call_output")
                        .put("call_id", "call_broken_done").put("output", "success"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("input", input)
                .put("tools", new JSONArray().put(responseFunction("lookup")))
                .put("tool_choice", "auto");
        JSONObject response = invokeJson(handleResponses, body);
        JSONObject message = response.getJSONArray("output").getJSONObject(0);
        String text = message.getJSONArray("content").getJSONObject(0).getString("text");
        check("MALFORMED_REPEAT_RECOVERED".equals(text),
                "a malformed repeated call escaped completed-tool recovery");
    }

    private static void codexCompatibilityModelAlias() throws Exception {
        Method modelsResponse = LocalApiGateway.class.getDeclaredMethod("modelsResponse");
        modelsResponse.setAccessible(true);
        JSONObject models = (JSONObject) modelsResponse.invoke(null);
        JSONArray data = models.getJSONArray("data");
        JSONObject alias = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject candidate = data.getJSONObject(i);
            if ("gpt-5.4".equals(candidate.optString("id"))) alias = candidate;
        }
        check(alias != null, "Codex compatibility model is missing from /v1/models");
        check("deepseek-chat".equals(alias.getString("alias_for")),
                "Codex compatibility model does not disclose its DeepSeek alias");
        check("default".equals(alias.getString("native_model")),
                "Codex compatibility model maps to the wrong native model");

        JSONObject response = invokeJson(handleResponses,
                new JSONObject().put("model", "gpt-5.4").put("input", "alias"));
        check("gpt-5.4".equals(response.getString("model")),
                "Responses did not preserve the requested compatibility model name");
    }

    private static void codexResponsesContract() throws Exception {
        JSONObject metadataA = new JSONObject().put("thread_id", "thread-codex-a")
                .put("session_id", "session-codex-a");
        JSONArray rootInput = new JSONArray().put(new JSONObject().put("type", "message")
                .put("role", "user").put("content", new JSONArray().put(new JSONObject()
                        .put("type", "input_text").put("text", "stable Codex root"))));
        JSONObject firstBody = new JSONObject().put("model", "gpt-5.4")
                .put("input", rootInput).put("client_metadata", metadataA);
        JSONObject sameBody = new JSONObject(firstBody.toString());
        JSONObject otherThread = new JSONObject(firstBody.toString()).put("client_metadata",
                new JSONObject().put("thread_id", "thread-codex-b")
                        .put("session_id", "session-codex-a"));
        JSONObject clearedRoot = new JSONObject(firstBody.toString()).put("input",
                new JSONArray().put(new JSONObject().put("type", "message")
                        .put("role", "user").put("content", "new Codex transcript")));

        LocalApiGateway.CompletionRequest first = (LocalApiGateway.CompletionRequest)
                translateResponses.invoke(null, "codex-scope-a", firstBody);
        LocalApiGateway.CompletionRequest same = (LocalApiGateway.CompletionRequest)
                translateResponses.invoke(null, "codex-scope-b", sameBody);
        LocalApiGateway.CompletionRequest rotated = (LocalApiGateway.CompletionRequest)
                translateResponses.invoke(null, "codex-scope-c", otherThread);
        LocalApiGateway.CompletionRequest cleared = (LocalApiGateway.CompletionRequest)
                translateResponses.invoke(null, "codex-scope-d", clearedRoot);
        check(first.clientSessionScope != null
                        && first.clientSessionScope.equals(same.clientSessionScope),
                "stable Codex client_metadata did not reuse its native branch");
        check(!first.clientSessionScope.equals(rotated.clientSessionScope),
                "a new Codex thread_id reused the prior native branch");
        check(!first.clientSessionScope.equals(cleared.clientSessionScope),
                "a cleared Codex transcript reused the prior native branch");
        check(!first.clientSessionScope.contains("thread-codex"),
                "raw Codex thread identity leaked into persistent session state");

        JSONObject textResponse = invokeJson(handleResponses, firstBody);
        JSONObject message = textResponse.getJSONArray("output").getJSONObject(0);
        check(textResponse.getBoolean("end_turn"),
                "completed Codex text response did not advertise end_turn");
        check("final_answer".equals(message.getString("phase")),
                "Codex message phase is not final_answer");

        JSONObject custom = new JSONObject().put("model", "gpt-5.4")
                .put("input", "CODEX_CUSTOM_PATCH")
                .put("tools", new JSONArray().put(new JSONObject().put("type", "custom")
                        .put("name", "apply_patch").put("description", "Apply a patch")
                        .put("format", new JSONObject().put("type", "grammar")
                                .put("syntax", "lark").put("definition", "start: /.+/s"))))
                .put("tool_choice", new JSONObject().put("type", "custom")
                        .put("name", "apply_patch"));
        JSONObject toolResponse = invokeJson(handleResponses, custom);
        JSONObject tool = toolResponse.getJSONArray("output").getJSONObject(0);
        check(!toolResponse.getBoolean("end_turn"),
                "Codex custom tool call incorrectly ended the agent turn");
        check("custom_tool_call".equals(tool.getString("type"))
                        && "completed".equals(tool.getString("status"))
                        && tool.getString("input").contains("*** Begin Patch"),
                "Codex custom apply_patch item lost its type, status, or free-form input");
    }

    private static void codexTransportItemsStayOpaque() throws Exception {
        JSONArray input = new JSONArray()
                .put(new JSONObject().put("type", "message").put("role", "user")
                        .put("content", "inspect transport filtering"))
                .put(new JSONObject().put("type", "reasoning")
                        .put("summary", new JSONArray().put(new JSONObject()
                                .put("type", "summary_text").put("text", "PUBLIC_SUMMARY")))
                        .put("encrypted_content", "SECRET_REASONING_CIPHER"))
                .put(new JSONObject().put("type", "context_compaction")
                        .put("encrypted_content", "SECRET_COMPACTION_CIPHER"))
                .put(new JSONObject().put("type", "agent_message")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "output_text").put("text", "PUBLIC_AGENT_TEXT"))));
        JSONObject body = new JSONObject().put("model", "gpt-5.4").put("input", input);
        LocalApiGateway.CompletionRequest request = (LocalApiGateway.CompletionRequest)
                translateResponses.invoke(null, "codex-opaque", body);
        check(request.basePrompt.contains("PUBLIC_SUMMARY")
                        && request.basePrompt.contains("PUBLIC_AGENT_TEXT"),
                "public Codex reasoning summary or agent message was discarded");
        check(!request.basePrompt.contains("SECRET_REASONING_CIPHER")
                        && !request.basePrompt.contains("SECRET_COMPACTION_CIPHER")
                        && !request.basePrompt.contains("encrypted_content"),
                "opaque Codex transport data leaked into the upstream model prompt");
    }

    private static void anthropicNonStreamMessage() throws Exception {
        JSONObject response = invokeJson(handleAnthropic, anthropicBody(false, false));
        check("message".equals(response.getString("type")),
                "Anthropic non-stream response type is wrong");
        check("assistant".equals(response.getString("role")),
                "Anthropic non-stream role is wrong");
        check("FAKE_STREAM".equals(response.getJSONArray("content")
                        .getJSONObject(0).getString("text")),
                "Anthropic non-stream text is wrong");
        check("end_turn".equals(response.getString("stop_reason")),
                "Anthropic stop reason is wrong");
    }

    private static void anthropicSseText() throws Exception {
        String wire = invokeWire(handleAnthropic, anthropicBody(true, false));
        check(wire.contains("event: message_start"),
                "Anthropic message_start is missing");
        check(wire.contains("\"type\":\"text_delta\""),
                "Anthropic text_delta is missing");
        check(wire.contains("FAKE_"), "Anthropic streamed text is missing");
        check(wire.contains("event: message_stop"),
                "Anthropic message_stop is missing");
        check(!wire.contains("data: [DONE]"),
                "Anthropic stream incorrectly used the OpenAI terminator");
    }

    private static void anthropicSseToolCall() throws Exception {
        JSONObject body = anthropicBody(true, true);
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\""),
                "Anthropic streamed tool_use is missing");
        check(wire.contains("\"type\":\"input_json_delta\""),
                "Anthropic input_json_delta is missing");
        check(wire.contains("\"stop_reason\":\"tool_use\""),
                "Anthropic tool stop reason is wrong");
    }

    private static void anthropicStreamsThinkingWithTools() throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("thinking", new JSONObject().put("type", "enabled")
                        .put("budget_tokens", 512))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_INCREMENTAL_STREAM")))
                .put("tools", new JSONArray().put(anthropicFunction("lookup")))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        int thinking = wire.indexOf("\"type\":\"thinking_delta\"");
        int text = wire.indexOf("\"type\":\"text_delta\"");
        check(thinking >= 0 && text > thinking,
                "thinking/text deltas were not emitted in live block order");
        check(wire.contains("think-") && wire.contains("LIVE_") && wire.contains("TEXT"),
                "native deltas were replaced by one buffered response");
        check(wire.contains("\"type\":\"signature_delta\""),
                "live thinking block was not signed before closing");
    }

    private static void anthropicHidesTaggedToolEnvelope() throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject().put("type", "string"))
                        .put("content", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path").put("content"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("thinking", new JSONObject().put("type", "enabled")
                        .put("budget_tokens", 512))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_TAGGED_TOOL_STREAM")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Write")
                        .put("description", "Write a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Write\""),
                "tagged native output did not become an Anthropic tool_use block");
        check(!wire.contains("tool▁call") && !wire.contains("functions.Write:0"),
                "private native tool tags leaked into the SSE text stream");
    }

    private static void anthropicHidesEmbeddedToolEnvelope() throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject().put("type", "string"))
                        .put("content", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path").put("content"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("thinking", new JSONObject().put("type", "enabled")
                        .put("budget_tokens", 512))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_EMBEDDED_TOOL_STREAM")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Write")
                        .put("description", "Write a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("Reasoning remains visible.")
                        && wire.contains("Visible plan remains."),
                "safe content before an embedded tool envelope was lost");
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Write\""),
                "embedded tool envelope did not become a tool_use block");
        check(!wire.contains("deekseep_tool_calls")
                        && !wire.contains("ekseep_tool_calls"),
                "embedded private tool JSON leaked into thinking or text deltas");
    }

    private static void anthropicToolResultLoop() throws Exception {
        JSONObject first = invokeJson(handleAnthropic, anthropicBody(false, true));
        JSONObject tool = first.getJSONArray("content").getJSONObject(0);
        check("tool_use".equals(tool.getString("type")),
                "Anthropic initial tool call is missing");
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user")
                        .put("content", "look it up"))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(tool)))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", tool.getString("id"))
                                .put("content", "agent result"))));
        JSONObject secondBody = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("messages", messages)
                .put("tools", new JSONArray().put(anthropicFunction("lookup")));
        JSONObject second = invokeJson(handleAnthropic, secondBody);
        check("TOOL_LOOP_DONE".equals(second.getJSONArray("content")
                        .getJSONObject(0).getString("text")),
                "Anthropic tool result did not continue the loop");
    }

    private static void anthropicHidesNarratedToolTranscript() throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("file_path",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_NARRATED_TOOL_STREAM")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Read")
                        .put("description", "Read a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Read\""),
                "narrated function did not become a structured tool_use");
        check(!wire.contains("Requested tool") && !wire.contains("[assistant]")
                        && !wire.contains("fabricated"),
                "narrated/fabricated tool transcript leaked into public SSE");
    }

    private static void anthropicRepairsDeferredAgentAction() throws Exception {
        JSONObject readSchema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("file_path",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_DEFERRED_ACTION")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Read")
                        .put("description", "Read a file").put("input_schema", readSchema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Read\""),
                "intention-only Agent turn was not repaired into a structured tool call");
        check(!wire.contains("我将读取文件"),
                "discarded intention narration leaked into Claude Code's visible stream");
    }

    private static void anthropicRepairsCodeInsteadOfWriting() throws Exception {
        JSONObject writeSchema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject().put("type", "string"))
                        .put("content", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path").put("content"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "请创建文件并写入内容 ANTHROPIC_CODE_INSTEAD_OF_WRITE")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Write")
                        .put("description", "Write a file").put("input_schema", writeSchema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Write\"")
                        && wire.contains("created-by-tool.txt"),
                "a code-only file answer was not repaired into a real Write tool call");
        check(!wire.contains("TOOL_DID_NOT_WRITE_THIS") && !wire.contains("```text"),
                "the code-only substitute leaked into the Anthropic response");
    }

    private static void anthropicRepairsSearchReadPromise() throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_SEARCH_READ_PROMISE")))
                .put("tools", new JSONArray().put(anthropicReadTool()))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("search-result.txt"),
                "search/read promise ended without starting a real tool action");
        check(!wire.contains("我来帮你搜索") && !wire.contains("我先读取"),
                "search/read promise leaked as a completed assistant answer");
    }

    private static void anthropicUsesSecondAgentRepair() throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_SECOND_REPAIR")))
                .put("tools", new JSONArray().put(anthropicReadTool()))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("second-repair.txt"),
                "second bounded Agent repair did not recover a narrated first repair");
        check(!wire.contains("我会先继续分析") && !wire.contains("event: error"),
                "first repair narration leaked or ended the Anthropic stream");
    }

    private static void anthropicFallsBackToSafeContinuation() throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("pattern",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("pattern"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_CONTINUATION_FALLBACK")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Glob")
                        .put("description", "List matching files").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Glob\"")
                        && wire.contains("event: message_stop"),
                "stubborn action narration did not become a harmless continuation tool");
        check(!wire.contains("我现在会继续") && !wire.contains("event: error"),
                "continuation fallback leaked narration or returned a retryable SSE error");
    }

    private static void anthropicRepairsDeferredThinkingAction() throws Exception {
        JSONObject readSchema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("file_path",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_DEFERRED_THINK_ACTION")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Read")
                        .put("description", "Read a file").put("input_schema", readSchema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Read\"")
                        && wire.contains("deferred-thinking.txt"),
                "action promised only in THINK was not repaired into a tool_use");
        check(!wire.contains("\"type\":\"text_delta\",\"text\":\"(no content)\""),
                "empty deferred Agent turn leaked a synthetic no-content answer");
    }

    private static void anthropicConvertsClaudeDisplayShorthand() throws Exception {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("command", new JSONObject().put("type", "string"))
                        .put("description", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("command"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_SHORTHAND_TOOL_STREAM")))
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Bash")
                        .put("description", "Run a shell command").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Bash\"")
                        && wire.contains("\"type\":\"input_json_delta\""),
                "Claude display shorthand did not become Anthropic tool events");
        check(wire.contains("\"stop_reason\":\"tool_use\""),
                "shorthand tool response did not end with tool_use");
        check(!wire.contains("Bash({") && !wire.contains("Show current directory\"})"),
                "shorthand tool notation leaked into public SSE text");
    }

    private static void anthropicSuppressesBashMetadataDuplicate() throws Exception {
        JSONObject priorTool = new JSONObject().put("type", "tool_use")
                .put("id", "toolu_bash_done").put("name", "Bash")
                .put("input", new JSONObject().put("command", "pwd")
                        .put("description", "Print current working directory"));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_BASH_METADATA_DUPLICATE"))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(priorTool)))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_bash_done")
                                .put("content", "/workspace"))));
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("command", new JSONObject().put("type", "string"))
                        .put("description", new JSONObject().put("type", "string"))
                        .put("timeout", new JSONObject().put("type", "integer")))
                .put("required", new JSONArray().put("command"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("messages", messages)
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Bash")
                        .put("description", "Run a shell command").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        JSONObject response = invokeJson(handleAnthropic, body);
        check("BASH_DUPLICATE_RECOVERED".equals(response.getJSONArray("content")
                        .getJSONObject(0).getString("text")),
                "Claude Bash metadata change bypassed completed-call recovery");
    }

    private static void anthropicAllowsRepeatedReadVerification() throws Exception {
        JSONObject priorTool = new JSONObject().put("type", "tool_use")
                .put("id", "toolu_read_done").put("name", "Read")
                .put("input", new JSONObject().put("file_path", "repeat.txt"));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_REPEAT_READ"))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(priorTool)))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_read_done")
                                .put("content", "A"))))
                .put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_REPEAT_READ verify again"));
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("file_path",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true).put("messages", messages)
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Read")
                        .put("description", "Read a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Read\""),
                "an intentional repeated Read was incorrectly suppressed");
    }

    private static void anthropicAllowsReadAfterEditVerification() throws Exception {
        JSONObject readInput = new JSONObject().put("file_path", "repeat.txt");
        JSONObject editInput = new JSONObject().put("file_path", "repeat.txt")
                .put("old_string", "A").put("new_string", "A\nB")
                .put("replace_all", false);
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_REPEAT_READ_AFTER_EDIT"))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_use").put("id", "toolu_read_before_edit")
                                .put("name", "Read").put("input", readInput))))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_read_before_edit")
                                .put("content", "A"))))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_use").put("id", "toolu_edit_between_reads")
                                .put("name", "Edit").put("input", editInput))))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_edit_between_reads")
                                .put("content", "updated"))));
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("file_path",
                        new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true).put("messages", messages)
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Read")
                        .put("description", "Read a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("\"type\":\"tool_use\"")
                        && wire.contains("\"name\":\"Read\""),
                "a verification Read after Edit was incorrectly suppressed");
    }

    private static void anthropicEndsStubbornDuplicateWithoutRetryError() throws Exception {
        JSONObject input = new JSONObject().put("file_path", "repeat.txt")
                .put("old_string", "A").put("new_string", "B");
        JSONObject priorTool = new JSONObject().put("type", "tool_use")
                .put("id", "toolu_edit_done").put("name", "Edit").put("input", input);
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_STUBBORN_DUPLICATE"))
                .put(new JSONObject().put("role", "assistant")
                        .put("content", new JSONArray().put(priorTool)))
                .put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(new JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_edit_done")
                                .put("content", "updated"))));
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject()
                        .put("file_path", new JSONObject().put("type", "string"))
                        .put("old_string", new JSONObject().put("type", "string"))
                        .put("new_string", new JSONObject().put("type", "string")))
                .put("required", new JSONArray().put("file_path")
                        .put("old_string").put("new_string"));
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", true).put("messages", messages)
                .put("tools", new JSONArray().put(new JSONObject().put("name", "Edit")
                        .put("description", "Edit a file").put("input_schema", schema)))
                .put("tool_choice", new JSONObject().put("type", "auto"));
        String wire = invokeWire(handleAnthropic, body);
        check(wire.contains("duplicate call was suppressed")
                        && wire.contains("event: message_stop"),
                "stubborn duplicate did not end as a safe completed turn");
        check(!wire.contains("event: error") && !wire.contains("deekseep_tool_calls"),
                "stubborn duplicate leaked markup or triggered a retryable SSE error");
    }

    private static void anthropicThinkingBlock() throws Exception {
        JSONObject body = new JSONObject().put("model", "claude-sonnet-4")
                .put("max_tokens", 1024)
                .put("thinking", new JSONObject().put("type", "enabled")
                        .put("budget_tokens", 512))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "ANTHROPIC_THINK")));
        JSONObject response = invokeJson(handleAnthropic, body);
        check("thinking".equals(response.getJSONArray("content")
                        .getJSONObject(0).getString("type")),
                "Anthropic thinking block is missing");
        check(response.getJSONArray("content").getJSONObject(0)
                        .getString("signature").startsWith("deekseep-local-"),
                "Anthropic thinking signature is missing");
    }

    private static void anthropicCountTokens() throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", "count me")));
        JSONObject response = invokeJson(handleAnthropicCountTokens, body);
        check(response.getInt("input_tokens") > 0,
                "Anthropic count_tokens returned no input tokens");
    }

    private static JSONObject anthropicBody(boolean stream, boolean tools) throws Exception {
        JSONObject body = new JSONObject().put("model", "deepseek-chat")
                .put("max_tokens", 1024).put("stream", stream)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "look it up")));
        if (tools) {
            body.put("tools", new JSONArray().put(anthropicFunction("lookup")))
                    .put("tool_choice", new JSONObject().put("type", "any"));
        }
        return body;
    }

    private static JSONObject anthropicFunction(String name) throws Exception {
        return new JSONObject().put("name", name).put("description", "lookup")
                .put("input_schema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("query",
                                new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("query")));
    }

    private static JSONObject anthropicReadTool() throws Exception {
        return new JSONObject().put("name", "Read").put("description", "Read a file")
                .put("input_schema", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("file_path",
                                new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path")));
    }

    private static JSONObject chatBody(boolean stream) throws Exception {
        return new JSONObject().put("model", "deepseek-chat").put("stream", stream)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "look it up")))
                .put("tools", new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", responseFunction("lookup"))))
                .put("tool_choice", "required")
                .put("stream_options", new JSONObject().put("include_usage", true));
    }

    private static JSONObject responseFunction(String name) throws Exception {
        return new JSONObject().put("type", "function").put("name", name)
                .put("description", "lookup")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("query",
                                new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("query")));
    }

    private static JSONObject invokeJson(Method method, JSONObject body) throws Exception {
        String wire = invokeWire(method, body);
        int split = wire.indexOf("\r\n\r\n");
        check(split >= 0, "HTTP response headers are incomplete");
        return new JSONObject(wire.substring(split + 4));
    }

    private static String invokeWire(Method method, JSONObject body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        activeWire = out;
        try {
            method.invoke(null, out, body);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        } finally {
            activeWire = null;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
