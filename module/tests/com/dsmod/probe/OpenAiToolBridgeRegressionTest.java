package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class OpenAiToolBridgeRegressionTest {
    public static void main(String[] args) throws Exception {
        parsesChatFunctionEnvelope();
        requiresWorkspaceWritesThroughTools();
        enforcesForcedToolChoice();
        acceptsResponsesShellCall();
        acceptsCustomAndApplyPatchCalls();
        acceptsCodexNamespaceAndHostedTools();
        preservesCustomGrammarAndRejectsSimulation();
        parsesDeepSeekAndClaudeToolTags();
        parsesOmniRouteCanonicalAndLooseJson();
        parsesOmniRouteDeepSeekVariants();
        resolvesOmniRouteNamesConservatively();
        recoversNarratedFunctionWithoutFabricatedResults();
        recoversClaudeDisplayShorthandCalls();
        canonicalizesCompletedCallSignatures();
        canonicalizesClaudeBashMetadata();
        rejectsMalformedArguments();
        toleratesMarkdownFence();
        System.out.println("OpenAiToolBridgeRegressionTest OK");
    }

    private static void parsesChatFunctionEnvelope() throws Exception {
        JSONObject function = new JSONObject().put("name", "get_weather")
                .put("description", "Get weather")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("city",
                                new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("city")));
        JSONArray tools = new JSONArray().put(new JSONObject().put("type", "function")
                .put("function", function));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(tools, "auto", true);
        String prompt = OpenAiToolBridge.addInstructions("weather?", plan, "req-1");
        check(prompt.contains("<tool>{\"name\": \"<tool_name>\""),
                "OmniRoute tool protocol was not appended");
        check(!prompt.contains("{\"" + OpenAiToolBridge.ENVELOPE_KEY + "\":"),
                "legacy private envelope is still the primary prompt contract");
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"get_weather\","
                        + "\"arguments\":{\"city\":\"Shanghai\"}}]}", plan);
        check(calls.size() == 1, "chat function call was not parsed");
        check("get_weather".equals(calls.get(0).name), "wrong function name");
        check("Shanghai".equals(new JSONObject(calls.get(0).arguments).getString("city")),
                "wrong function arguments");
    }

    private static void requiresWorkspaceWritesThroughTools() throws Exception {
        JSONObject write = new JSONObject().put("name", "Write")
                .put("description", "Write a file")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("file_path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path").put("content")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", write)), "auto", false);
        String prompt = OpenAiToolBridge.addInstructions(
                "Create app.js in the workspace", plan, "private-request-id");
        check(prompt.contains("MUST call an appropriate file-writing or editing tool")
                        && prompt.contains("Do not print a code block"),
                "workspace file requests were not forced through the supplied tools");
        String lower = prompt.toLowerCase(java.util.Locale.US);
        check(!lower.contains("deepseek local api") && !lower.contains("deekseep")
                        && !lower.contains("local agent tool protocol")
                        && !prompt.contains("private-request-id"),
                "model-facing tool instructions disclosed the API service identity");
    }

    private static void enforcesForcedToolChoice() throws Exception {
        JSONArray tools = new JSONArray()
                .put(responseFunction("first"))
                .put(responseFunction("second"));
        JSONObject forced = new JSONObject().put("type", "function").put("name", "second");
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(tools, forced, false);
        check(OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"first\",\"arguments\":{}}]}",
                plan).isEmpty(), "forced choice accepted the wrong tool");
        check(OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"second\",\"arguments\":{}}]}",
                plan).size() == 1, "forced choice rejected the selected tool");
    }

    private static void acceptsResponsesShellCall() throws Exception {
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(
                new JSONArray().put(new JSONObject().put("type", "shell")), "required", true);
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"shell\","
                        + "\"arguments\":{\"commands\":[\"pwd\",\"rg --files\"],"
                        + "\"timeout_ms\":10000}}]}", plan);
        check(calls.size() == 1, "shell call was not parsed");
        check(OpenAiToolBridge.SHELL.equals(calls.get(0).kind), "wrong shell kind");
    }

    private static void acceptsCustomAndApplyPatchCalls() throws Exception {
        JSONArray tools = new JSONArray()
                .put(new JSONObject().put("type", "custom").put("name", "freeform"))
                .put(new JSONObject().put("type", "apply_patch"));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(tools, "auto", true);
        String output = "{\"deekseep_tool_calls\":["
                + "{\"name\":\"freeform\",\"arguments\":{\"input\":\"hello\"}},"
                + "{\"name\":\"apply_patch\",\"arguments\":{\"type\":\"update_file\","
                + "\"path\":\"README.md\",\"diff\":\"@@ -1 +1 @@\"}}]}";
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(output, plan);
        check(calls.size() == 1, "action tools were not serialized");
        check("hello".equals(calls.get(0).customInput()), "custom input was changed");
    }

    private static void acceptsCodexNamespaceAndHostedTools() throws Exception {
        JSONObject nested = responseFunction("spawn_agent");
        JSONObject namespace = new JSONObject().put("type", "namespace")
                .put("name", "multi_agent_v1").put("description", "agents")
                .put("tools", new JSONArray().put(nested));
        JSONArray tools = new JSONArray().put(responseFunction("exec_command"))
                .put(namespace).put(new JSONObject().put("type", "web_search")
                        .put("external_web_access", false));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(tools, "auto", true);
        check(plan.tools.size() == 2, "namespace was not flattened or hosted tool not omitted");
        String prompt = OpenAiToolBridge.addInstructions("delegate", plan, "req-ns");
        check(prompt.contains("multi_agent_v1"), "namespace was not exposed to the model");
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"namespace\":\"multi_agent_v1\","
                        + "\"name\":\"spawn_agent\",\"arguments\":{}}]}", plan);
        check(calls.size() == 1, "namespaced function call was not parsed");
        check("multi_agent_v1".equals(calls.get(0).namespace),
                "function namespace was not preserved");
        check("spawn_agent".equals(calls.get(0).name), "nested function name changed");
    }

    private static void preservesCustomGrammarAndRejectsSimulation() throws Exception {
        JSONObject format = new JSONObject().put("type", "grammar").put("syntax", "lark")
                .put("definition", "start: \"*** Begin Patch\" LF \"*** End Patch\"");
        JSONObject patch = new JSONObject().put("type", "custom").put("name", "apply_patch")
                .put("description", "freeform patch").put("format", format);
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(
                new JSONArray().put(patch).put(responseFunction("exec_command")), "auto", true);
        String prompt = OpenAiToolBridge.addInstructions("edit", plan, "req-grammar");
        check(prompt.contains("*** Begin Patch"), "custom grammar was dropped from the prompt");
        check(OpenAiToolBridge.resemblesEnvelope(
                "Requested tool apply_patch with arguments:\n*** Begin Patch"),
                "narrated tool simulation was not rejected");
        List<OpenAiToolBridge.Call> recovered = OpenAiToolBridge.parseCalls(
                "{\"ekseep_tool_calls\":[{\"name\":\"apply_patch\","
                        + "\"arguments\":{\"input\":\"*** Begin Patch\\n*** End Patch\\n\"}},"
                        + "{\"name\":\"exec_command\",\"arguments\":{}}]}", plan);
        check(recovered.size() == 1 && "apply_patch".equals(recovered.get(0).name),
                "corrupt envelope recovery did not serialize the action tool");
        List<OpenAiToolBridge.Call> narrated = OpenAiToolBridge.parseCalls(
                "Request tool apply_patch with input:\n*** Begin Patch\n"
                        + "*** Add File: probe.txt\n+OK\n*** End Patch", plan);
        check(narrated.size() == 1
                        && narrated.get(0).customInput().contains("*** Add File: probe.txt"),
                "narrated custom call was not recovered");
    }

    private static void parsesDeepSeekAndClaudeToolTags() throws Exception {
        JSONObject write = new JSONObject().put("name", "Write")
                .put("description", "Write a file")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("file_path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path").put("content")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", write)), "auto", false);

        String special = "<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>functions.Write:0"
                + "<｜tool▁call▁argument▁begin｜>{\"file_path\":\"probe.txt\","
                + "\"content\":\"LINE_ONE\"}<｜tool▁call▁end｜><｜tool▁calls▁end｜>";
        List<OpenAiToolBridge.Call> specialCalls = OpenAiToolBridge.parseCalls(special, plan);
        check(specialCalls.size() == 1 && "Write".equals(specialCalls.get(0).name),
                "DeepSeek special-token tool call was not recovered");

        String xml = "<function_calls><invoke name=\"Write\">"
                + "<parameter name=\"file_path\">probe.txt</parameter>"
                + "<parameter name=\"content\">LINE&amp;TWO</parameter>"
                + "</invoke></function_calls>";
        List<OpenAiToolBridge.Call> xmlCalls = OpenAiToolBridge.parseCalls(xml, plan);
        check(xmlCalls.size() == 1
                        && "LINE&TWO".equals(new JSONObject(xmlCalls.get(0).arguments)
                                .getString("content")),
                "Claude XML invoke tool call was not recovered");

        String recipient = "assistant to=functions.Write\n"
                + "{\"file_path\":\"probe.txt\",\"content\":\"LINE_THREE\"}";
        List<OpenAiToolBridge.Call> recipientCalls = OpenAiToolBridge.parseCalls(
                recipient, plan);
        check(recipientCalls.size() == 1,
                "assistant recipient tool call was not recovered");
        check(OpenAiToolBridge.resemblesEnvelope(special)
                        && OpenAiToolBridge.resemblesEnvelope(xml)
                        && OpenAiToolBridge.resemblesEnvelope(recipient),
                "tagged tool output was not classified as private markup");
    }

    private static void parsesOmniRouteCanonicalAndLooseJson() throws Exception {
        JSONObject bash = new JSONObject().put("name", "Bash")
                .put("description", "Run a command")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("command", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("command")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", bash)), "auto", true);

        List<OpenAiToolBridge.Call> canonical = OpenAiToolBridge.parseCalls(
                "Before\n<tool>{\"name\":\"Bash\",\"arguments\":"
                        + "{\"command\":\"pwd\"}}</tool>\nAfter", plan);
        check(canonical.size() == 1 && "pwd".equals(
                        new JSONObject(canonical.get(0).arguments).getString("command")),
                "canonical OmniRoute <tool> block was not parsed");

        List<OpenAiToolBridge.Call> loose = OpenAiToolBridge.parseCalls(
                "```python\n{'name':'Bash','arguments':{command:'ls',"
                        + "'enabled':True,'missing':None,},}\n```", plan);
        check(loose.size() == 1 && "ls".equals(
                        new JSONObject(loose.get(0).arguments).getString("command")),
                "OmniRoute loose/Python JSON normalization was not preserved");
    }

    private static void parsesOmniRouteDeepSeekVariants() throws Exception {
        JSONObject write = new JSONObject().put("name", "Write")
                .put("description", "Write a file")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("file_path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path").put("content")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", write)), "auto", true);

        String variants = "<tool><tool name=\"skill\">{\"name\":\"Write\","
                + "\"params\":{\"file_path\":\"a.txt\",\"content\":\"A\"}}"
                + "</tool></tool>\n"
                + "<tool:write>{\"file_path\":\"b.txt\",\"content\":\"B\"}</tool>\n"
                + "<tool id=\"1\"><name>Write</name><arguments>"
                + "{\"file_path\":\"c.txt\",\"content\":\"C\"}"
                + "</arguments></tool>";
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(variants, plan);
        check(calls.size() == 3, "nested/tag/attribute OmniRoute variants were not parsed");
        check("a.txt".equals(new JSONObject(calls.get(0).arguments)
                        .getString("file_path"))
                        && "b.txt".equals(new JSONObject(calls.get(1).arguments)
                                .getString("file_path"))
                        && "c.txt".equals(new JSONObject(calls.get(2).arguments)
                                .getString("file_path")),
                "DeepSeek variant arguments changed during parsing");

        List<OpenAiToolBridge.Call> parameters = OpenAiToolBridge.parseCalls(
                "<tool><parameter name=\"file_path\" content=\"d.txt\">"
                        + "<parameter name=\"content\">D&amp;E</parameter></tool>", plan);
        check(parameters.size() == 1 && "D&E".equals(
                        new JSONObject(parameters.get(0).arguments).getString("content")),
                "schema-based nameless parameter block was not parsed");
    }

    private static void resolvesOmniRouteNamesConservatively() throws Exception {
        JSONObject todo = new JSONObject().put("name", "TodoWrite")
                .put("description", "Update todos")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("todos",
                                new JSONObject().put("type", "array")))
                        .put("required", new JSONArray().put("todos")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", todo)), "auto", false);
        List<OpenAiToolBridge.Call> fuzzy = OpenAiToolBridge.parseCalls(
                "<tool:todowrite>{\"todos\":[]}</tool>", plan);
        check(fuzzy.size() == 1 && "TodoWrite".equals(fuzzy.get(0).name),
                "request-scoped normalized tool name was not recovered");
        check(OpenAiToolBridge.parseCalls(
                        "<tool>{\"name\":\"DeleteEverything\",\"arguments\":{}}</tool>",
                        plan).isEmpty(),
                "unrequested tool name was accepted");
        check(OpenAiToolBridge.resemblesEnvelope(
                        "Tool call: TodoWrite\n{\"todos\":[]}"),
                "textual tool label would leak instead of entering format repair");
    }

    private static void canonicalizesCompletedCallSignatures() throws Exception {
        String first = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "lookup", "{\"query\":\"agent\",\"limit\":2}");
        String reordered = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "lookup", new JSONObject().put("limit", 2).put("query", "agent"));
        String changed = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "lookup", "{\"query\":\"agent\",\"limit\":3}");
        check(first.equals(reordered), "tool signature depends on JSON object key order");
        check(!first.equals(changed), "tool signature merged different arguments");
    }

    private static void recoversNarratedFunctionWithoutFabricatedResults() throws Exception {
        JSONObject read = new JSONObject().put("name", "Read")
                .put("description", "Read a file")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("file_path",
                                new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path")));
        JSONObject write = new JSONObject().put("name", "Write")
                .put("description", "Write a file")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("file_path", new JSONObject().put("type", "string"))
                                .put("content", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("file_path").put("content")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(new JSONArray()
                .put(new JSONObject().put("type", "function").put("function", read))
                .put(new JSONObject().put("type", "function").put("function", write)),
                "auto", true);
        String narrated = "[assistant]\nRequested tool Read (call_id=made_up) "
                + "with arguments:\n{\"file_path\":\"input.txt\"}\n\n"
                + "[tool]\nTool result:\nfabricated\n\n[assistant]\n"
                + "Requested tool Write with arguments:\n"
                + "{\"file_path\":\"bad.txt\",\"content\":\"never execute\"}";
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(narrated, plan);
        check(calls.size() == 1 && "Read".equals(calls.get(0).name),
                "first narrated function call was not recovered alone");
        check("input.txt".equals(new JSONObject(calls.get(0).arguments)
                        .getString("file_path")),
                "narrated function arguments changed");
    }

    private static void recoversClaudeDisplayShorthandCalls() throws Exception {
        JSONObject bash = new JSONObject().put("name", "Bash")
                .put("description", "Execute a shell command")
                .put("parameters", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject()
                                .put("command", new JSONObject().put("type", "string"))
                                .put("description", new JSONObject().put("type", "string")))
                        .put("required", new JSONArray().put("command")));
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.chatPlan(
                new JSONArray().put(new JSONObject().put("type", "function")
                        .put("function", bash)), "auto", true);
        String captured = "我来分析当前目录。先获取目录结构和关键信息。\n"
                + "Bash({\"command\": \"pwd && ls -la\", "
                + "\"description\": \"Show current directory and list files\"})\n"
                + "Bash({\"command\": \"git log --oneline -5\", "
                + "\"description\": \"Show recent git commits\"})\n"
                + "Bash({\"command\": \"find . -maxdepth 2 -name \\\"*.md\\\"\", "
                + "\"description\": \"Find documentation files\"})";
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(captured, plan);
        check(calls.size() == 3, "Claude display shorthand did not become three calls");
        check("pwd && ls -la".equals(new JSONObject(calls.get(0).arguments)
                        .getString("command")),
                "first shorthand arguments changed");
        check(OpenAiToolBridge.resemblesCallSyntax(captured, plan),
                "shorthand call was not classified as private tool syntax");
        check(OpenAiToolBridge.shorthandCallStart("Ba", plan) < 0
                        && OpenAiToolBridge.possibleShorthandCallStart("Ba", plan) == 0,
                "partial shorthand would leak before the opening parenthesis");
        check(OpenAiToolBridge.parseCalls(
                        "Use Bash when a command is needed, but answer normally.", plan).isEmpty(),
                "ordinary prose mentioning Bash became an executable call");
    }

    private static void canonicalizesClaudeBashMetadata() throws Exception {
        String completed = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "Bash", new JSONObject().put("command", "pwd")
                        .put("description", "Print current working directory"));
        String regenerated = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "Bash", new JSONObject().put("command", "pwd")
                        .put("description", "Check current directory").put("timeout", 30000));
        String different = OpenAiToolBridge.signatureFor(OpenAiToolBridge.FUNCTION, null,
                "Bash", new JSONObject().put("command", "pwd -P").put("timeout", 30000));
        check(completed.equals(regenerated),
                "Claude Bash presentation metadata bypassed duplicate suppression");
        check(!completed.equals(different), "different Bash commands shared one signature");
    }

    private static void rejectsMalformedArguments() throws Exception {
        OpenAiToolBridge.Plan shell = OpenAiToolBridge.responsesPlan(
                new JSONArray().put(new JSONObject().put("type", "shell")), "auto", true);
        check(OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"shell\",\"arguments\":{}}]}",
                shell).isEmpty(), "empty shell arguments were accepted");

        OpenAiToolBridge.Plan patch = OpenAiToolBridge.responsesPlan(
                new JSONArray().put(new JSONObject().put("type", "apply_patch")), "auto", true);
        check(OpenAiToolBridge.parseCalls(
                "{\"deekseep_tool_calls\":[{\"name\":\"apply_patch\",\"arguments\":"
                        + "{\"type\":\"update_file\",\"path\":\"x\"}}]}", patch).isEmpty(),
                "patch update without diff was accepted");
    }

    private static void toleratesMarkdownFence() throws Exception {
        OpenAiToolBridge.Plan plan = OpenAiToolBridge.responsesPlan(
                new JSONArray().put(responseFunction("lookup")), "auto", true);
        List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(
                "```json\n{\"deekseep_tool_calls\":[{\"name\":\"lookup\","
                        + "\"arguments\":{}}]}\n```", plan);
        check(calls.size() == 1, "fenced tool call was not recovered");
    }

    private static JSONObject responseFunction(String name) throws Exception {
        return new JSONObject().put("type", "function").put("name", name)
                .put("description", name).put("parameters",
                        new JSONObject().put("type", "object"));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
