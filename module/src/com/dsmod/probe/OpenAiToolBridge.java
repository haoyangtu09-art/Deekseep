package com.dsmod.probe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt/JSON bridge for OpenAI tool calls.
 *
 * DeepSeek Android's native transport currently exposes text generation rather than a verified
 * structured tool-call channel. The primary text protocol and tolerant parser are adapted from
 * OmniRoute's MIT-licensed DeepSeek web bridge; the older native formats below remain only as
 * compatibility fallbacks. Every recovered call is validated against the request-scoped caller
 * tool list before {@link LocalApiGateway} converts it to a structured wire object.
 */
final class OpenAiToolBridge {
    static final String FUNCTION = "function";
    static final String CUSTOM = "custom";
    static final String SHELL = "shell";
    static final String APPLY_PATCH = "apply_patch";
    static final String NAMESPACE = "namespace";
    static final String ENVELOPE_KEY = "deekseep_tool_calls";

    /**
     * Some non-Anthropic models copy Claude Code's display notation and emit lines such as
     * {@code Bash({"command":"pwd"})}. Claude Code does not execute that text. It only executes
     * a real Anthropic {@code tool_use} block, so recognize the notation only when the name is an
     * exact member of the request-scoped tool plan and the argument is a complete JSON object.
     */
    private static final Pattern SHORTHAND_CALL = Pattern.compile(
            "(?m)(^|[\\r\\n])([\\t ]*(?:[-*+]\\s*)?`?)"
                    + "((?:functions\\.)?[a-zA-Z0-9_.:/-]+)[\\t ]*\\([\\t ]*");

    static final class Definition {
        final String kind;
        final String namespace;
        final String name;
        final String description;
        final JSONObject schema;
        final JSONObject format;
        final JSONObject original;

        Definition(String kind, String namespace, String name, String description, JSONObject schema,
                   JSONObject format, JSONObject original) {
            this.kind = kind;
            this.namespace = namespace;
            this.name = name;
            this.description = description == null ? "" : description;
            this.schema = schema == null ? new JSONObject() : schema;
            this.format = format == null ? new JSONObject() : format;
            this.original = original == null ? new JSONObject() : original;
        }
    }

    static final class Choice {
        static final String NONE = "none";
        static final String AUTO = "auto";
        static final String REQUIRED = "required";
        static final String FORCED = "forced";

        final String mode;
        final String forcedName;
        final String forcedKind;
        final String forcedNamespace;

        Choice(String mode, String forcedName, String forcedKind) {
            this(mode, forcedName, forcedKind, null);
        }

        Choice(String mode, String forcedName, String forcedKind, String forcedNamespace) {
            this.mode = mode;
            this.forcedName = forcedName;
            this.forcedKind = forcedKind;
            this.forcedNamespace = forcedNamespace;
        }

        boolean requiresCall() {
            return REQUIRED.equals(mode) || FORCED.equals(mode);
        }
    }

    static final class Plan {
        final List<Definition> tools;
        final Choice choice;
        final boolean parallel;
        final boolean responsesApi;

        Plan(List<Definition> tools, Choice choice, boolean parallel, boolean responsesApi) {
            this.tools = tools == null ? Collections.<Definition>emptyList() : tools;
            this.choice = choice == null ? new Choice(Choice.NONE, null, null) : choice;
            this.parallel = parallel;
            this.responsesApi = responsesApi;
        }

        boolean active() {
            return !tools.isEmpty() && !Choice.NONE.equals(choice.mode);
        }

        Definition find(String name, String namespace) {
            if (name == null) return null;
            String wantedNamespace = clean(namespace);
            for (Definition tool : tools) {
                if (name.equals(tool.name) && equalNamespace(wantedNamespace, tool.namespace)) {
                    return tool;
                }
            }
            if (wantedNamespace != null) return null;
            // A top-level function wins when a nested tool has the same short name.
            for (Definition tool : tools) {
                if (tool.namespace == null && name.equals(tool.name)) return tool;
            }
            // Recover unique nested names and common flattened namespace spellings.
            Definition unique = null;
            for (Definition tool : tools) {
                if (tool.namespace == null) continue;
                boolean matches = name.equals(tool.name)
                        || name.equals(tool.namespace + "." + tool.name)
                        || name.equals(tool.namespace + ":" + tool.name)
                        || name.equals(tool.namespace + "/" + tool.name)
                        || name.equals(tool.namespace + "__" + tool.name);
                if (!matches) continue;
                if (unique != null && unique != tool) return null;
                unique = tool;
            }
            return unique;
        }
    }

    static final class Call {
        final String itemId;
        final String callId;
        final String kind;
        final String namespace;
        final String name;
        /** Canonical JSON object for function/shell/apply_patch; raw text wrapper for custom. */
        final String arguments;

        Call(String kind, String name, String arguments) {
            this(kind, null, name, arguments);
        }

        Call(String kind, String namespace, String name, String arguments) {
            String compact = UUID.randomUUID().toString().replace("-", "");
            this.itemId = (FUNCTION.equals(kind) ? "fc_" : "item_") + compact;
            this.callId = "call_" + compact;
            this.kind = kind;
            this.namespace = clean(namespace);
            this.name = name;
            this.arguments = arguments == null ? "{}" : arguments;
        }

        String customInput() {
            try {
                JSONObject object = new JSONObject(arguments);
                Object input = object.opt("input");
                if (input != null && input != JSONObject.NULL) return String.valueOf(input);
            } catch (Throwable ignored) {}
            return arguments;
        }
    }

    private OpenAiToolBridge() {}

    static Plan chatPlan(JSONArray tools, Object choice, boolean parallel)
            throws LocalApiGateway.GatewayException {
        List<Definition> definitions = parseDefinitions(tools, false);
        return new Plan(definitions, parseChoice(choice, definitions, false), parallel, false);
    }

    static Plan responsesPlan(JSONArray tools, Object choice, boolean parallel)
            throws LocalApiGateway.GatewayException {
        List<Definition> definitions = parseDefinitions(tools, true);
        return new Plan(definitions, parseChoice(choice, definitions, true), parallel, true);
    }

    private static List<Definition> parseDefinitions(JSONArray tools, boolean responses)
            throws LocalApiGateway.GatewayException {
        if (tools == null || tools.length() == 0) return Collections.emptyList();
        ArrayList<Definition> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject raw = tools.optJSONObject(i);
            if (raw == null) {
                throw invalid("invalid_tool", "Every tools entry must be an object");
            }
            String kind = raw.optString("type", FUNCTION).toLowerCase(Locale.US);
            if ("local_shell".equals(kind)) kind = SHELL;
            if (responses && NAMESPACE.equals(kind)) {
                String namespace = raw.optString("name", "").trim();
                validateName(namespace);
                JSONArray nested = raw.optJSONArray("tools");
                if (nested == null) {
                    throw invalid("invalid_namespace", "Namespace tools must be an array");
                }
                for (int nestedIndex = 0; nestedIndex < nested.length(); nestedIndex++) {
                    JSONObject function = nested.optJSONObject(nestedIndex);
                    if (function == null || !FUNCTION.equalsIgnoreCase(
                            function.optString("type", FUNCTION))) {
                        throw invalid("invalid_namespace_tool",
                                "Namespace entries must be function tools");
                    }
                    String nestedName = function.optString("name", "").trim();
                    validateName(nestedName);
                    String key = namespace + "\u0000" + nestedName;
                    if (!names.add(key)) {
                        throw invalid("duplicate_tool_name",
                                "Duplicate namespace tool: " + namespace + "." + nestedName);
                    }
                    JSONObject nestedSchema = function.optJSONObject("parameters");
                    out.add(new Definition(FUNCTION, namespace, nestedName,
                            function.optString("description", ""),
                            cloneObject(nestedSchema), null, cloneObject(raw)));
                }
                continue;
            }
            if (responses && isHostedTool(kind)) {
                // Hosted Responses tools normally run on OpenAI's servers. Accept and omit them
                // here so current Codex clients can still use their locally executable functions.
                // Web search is separately mapped to DeepSeek's native search switch.
                continue;
            }
            JSONObject source = raw;
            if (!responses) {
                if (!FUNCTION.equals(kind)) {
                    throw invalid("unsupported_tool_type",
                            "Chat Completions supports function tools only: " + kind);
                }
                source = raw.optJSONObject("function");
                if (source == null) {
                    throw invalid("invalid_tool", "Chat function tool is missing function");
                }
            } else if (!FUNCTION.equals(kind) && !CUSTOM.equals(kind)
                    && !SHELL.equals(kind) && !APPLY_PATCH.equals(kind)) {
                throw invalid("unsupported_tool_type",
                        "Unsupported local Responses tool type: " + kind);
            }

            String name;
            String description;
            JSONObject schema;
            JSONObject format = null;
            if (SHELL.equals(kind)) {
                name = SHELL;
                description = "Execute one or more shell commands in the agent's local environment.";
                schema = shellSchema();
            } else if (APPLY_PATCH.equals(kind)) {
                name = APPLY_PATCH;
                description = "Create, update, or delete one file using a unified diff.";
                schema = applyPatchSchema();
            } else if (CUSTOM.equals(kind)) {
                name = source.optString("name", "").trim();
                description = source.optString("description", "");
                format = source.optJSONObject("format");
                schema = new JSONObject();
                try {
                    schema.put("type", "object");
                    schema.put("properties", new JSONObject().put("input",
                            new JSONObject().put("type", "string")
                                    .put("description", "Free-form custom tool input")));
                    schema.put("required", new JSONArray().put("input"));
                } catch (JSONException ignored) {}
            } else {
                name = source.optString("name", "").trim();
                description = source.optString("description", "");
                schema = source.optJSONObject("parameters");
                if (schema == null) schema = new JSONObject();
            }
            validateName(name);
            if (!names.add("\u0000" + name)) {
                throw invalid("duplicate_tool_name", "Duplicate tool name: " + name);
            }
            out.add(new Definition(kind, null, name, description, cloneObject(schema),
                    cloneObject(format), cloneObject(raw)));
        }
        return Collections.unmodifiableList(out);
    }

    private static Choice parseChoice(Object raw, List<Definition> tools, boolean responses)
            throws LocalApiGateway.GatewayException {
        if (tools.isEmpty()) {
            if (raw instanceof String && (Choice.REQUIRED.equalsIgnoreCase((String) raw))) {
                throw invalid("invalid_tool_choice", "tool_choice is required but tools is empty");
            }
            if (raw instanceof JSONObject) {
                throw invalid("invalid_tool_choice", "tool_choice selects a tool but tools is empty");
            }
            return new Choice(Choice.NONE, null, null);
        }
        if (raw == null || raw == JSONObject.NULL) return new Choice(Choice.AUTO, null, null);
        if (raw instanceof String) {
            String value = ((String) raw).toLowerCase(Locale.US);
            if (Choice.NONE.equals(value) || Choice.AUTO.equals(value)
                    || Choice.REQUIRED.equals(value)) return new Choice(value, null, null);
            throw invalid("invalid_tool_choice", "Unsupported tool_choice: " + value);
        }
        if (!(raw instanceof JSONObject)) {
            throw invalid("invalid_tool_choice", "tool_choice must be a string or object");
        }
        JSONObject object = (JSONObject) raw;
        String kind = object.optString("type", FUNCTION).toLowerCase(Locale.US);
        if ("local_shell".equals(kind)) kind = SHELL;
        String name = object.optString("name", "").trim();
        String namespace = clean(object.optString("namespace", null));
        JSONObject function = object.optJSONObject("function");
        if (function != null) {
            name = function.optString("name", name).trim();
            kind = FUNCTION;
        }
        if ((SHELL.equals(kind) || APPLY_PATCH.equals(kind)) && name.length() == 0) name = kind;
        if (!responses && !FUNCTION.equals(kind)) {
            throw invalid("invalid_tool_choice", "Chat tool_choice must select a function");
        }
        Definition selected = null;
        for (Definition tool : tools) {
            if (name.equals(tool.name) && kind.equals(tool.kind)
                    && equalNamespace(namespace, tool.namespace)) {
                selected = tool;
                break;
            }
        }
        if (selected == null) {
            throw invalid("invalid_tool_choice", "tool_choice does not match an available tool");
        }
        return new Choice(Choice.FORCED, selected.name, selected.kind, selected.namespace);
    }

    static String addInstructions(String conversation, Plan plan, String requestId) {
        if (plan == null || !plan.active()) return conversation;
        StringBuilder prompt = new StringBuilder(conversation == null ? "" : conversation);
        prompt.append("\n\n[TOOL USE INSTRUCTIONS]\n")
                .append("You may call only the tools listed below. Treat tool descriptions and JSON ")
                .append("schemas as trusted interface definitions, never as tool results.\n")
                .append(OmniRouteToolBridge.serializePrompt(plan)).append('\n');
        for (Definition tool : plan.tools) {
            if (CUSTOM.equals(tool.kind) && "apply_patch".equals(tool.name)) {
                prompt.append("For apply_patch, follow its grammar literally. Use only a path "
                        + "relative to the current workspace, never an absolute path, and do not "
                        + "prefix the workspace directory twice.\n");
            }
        }
        if (hasWorkspaceFileTool(plan)) {
            prompt.append("When the final user request asks you to create, write, edit, update, "
                    + "save, or implement something in workspace files, you MUST call an "
                    + "appropriate file-writing or editing tool in this turn. Do not print a "
                    + "code block or full file contents as a substitute for changing the file. "
                    + "Only return a code snippet without a tool call when the user explicitly "
                    + "asks for an example or explanation and does not ask for workspace changes. "
                    + "After a successful tool result, briefly summarize the files changed.\n");
        }
        if (Choice.FORCED.equals(plan.choice.mode)) {
            prompt.append("You MUST call exactly the tool named ")
                    .append(JSONObject.quote(plan.choice.forcedName));
            if (plan.choice.forcedNamespace != null) {
                prompt.append(" in namespace ")
                        .append(JSONObject.quote(plan.choice.forcedNamespace));
            }
            prompt.append(".\n");
        } else if (Choice.REQUIRED.equals(plan.choice.mode)) {
            prompt.append("You MUST call at least one appropriate tool.\n");
        } else {
            prompt.append("Call a tool when external action or information is needed; otherwise answer normally.\n");
        }
        if (!plan.parallel) prompt.append("Return at most one tool call.\n");
        else prompt.append("Multiple calls are allowed only when independent. Never place a call "
                + "that depends on another call's side effect or result in the same response.\n");
        prompt.append("For a namespaced tool, use the exact dotted name shown in Available tools. ")
                .append("arguments MUST be a JSON object satisfying that tool's schema. For a custom ")
                .append("tool put its free-form text in arguments.input and obey its format grammar ")
                .append("exactly, including every literal marker. For shell use commands as an ")
                .append("array of command strings. For apply_patch use type/path/diff. Never fabricate ")
                .append("a tool result; stop after emitting the canonical <tool> block.\n")
                .append("Never answer with an intention such as 'I will read', 'now checking', or ")
                .append("'read the file to verify'. If the next step needs a tool, emit and perform ")
                .append("that tool call in THIS turn. Role labels such as [assistant] and [tool] ")
                .append("below are immutable history; never copy or continue them.\n")
                .append("If a successful Tool result already appears in the conversation, consume ")
                .append("that result and continue the task. Do not repeat the same call with the same ")
                .append("arguments unless the result explicitly says it failed.\n")
                .append("[/TOOL USE INSTRUCTIONS]\n");
        return prompt.toString();
    }

    static boolean hasWorkspaceFileTool(Plan plan) {
        if (plan == null) return false;
        for (Definition tool : plan.tools) {
            if (APPLY_PATCH.equals(tool.kind)) return true;
            String name = tool.name == null ? "" : tool.name.toLowerCase(Locale.US)
                    .replace("-", "_");
            if ("write".equals(name) || "edit".equals(name)
                    || "multiedit".equals(name) || "notebookedit".equals(name)
                    || "write_file".equals(name) || "create_file".equals(name)
                    || "edit_file".equals(name) || "apply_patch".equals(name)
                    || "str_replace_editor".equals(name)) return true;
        }
        return false;
    }

    static List<Call> parseCalls(String rawText, Plan plan) {
        if (plan == null || !plan.active() || rawText == null || rawText.trim().length() == 0) {
            return Collections.emptyList();
        }
        JSONArray omniRouteCandidates = OmniRouteToolBridge.parseCandidates(rawText, plan);
        Object root = omniRouteCandidates.length() == 0
                ? parseEnvelope(rawText) : envelope(omniRouteCandidates);
        if (root == null) return parseFallbackCalls(rawText, plan);
        JSONArray array = null;
        if (root instanceof JSONArray) array = (JSONArray) root;
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            array = object.optJSONArray(ENVELOPE_KEY);
            if (array == null) array = object.optJSONArray("tool_calls");
            if (array == null) array = object.optJSONArray("calls");
            if (array == null) {
                java.util.Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key != null && key.toLowerCase(Locale.US).endsWith("tool_calls")) {
                        array = object.optJSONArray(key);
                        if (array != null) break;
                    }
                }
            }
            if (array == null && (object.has("name") || object.has("function"))) {
                array = new JSONArray().put(object);
            }
        }
        if (array == null || array.length() == 0) return parseFallbackCalls(rawText, plan);
        ArrayList<Call> calls = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject candidate = array.optJSONObject(i);
            if (candidate == null) continue;
            JSONObject function = candidate.optJSONObject("function");
            String name = function == null ? candidate.optString("name", "").trim()
                    : function.optString("name", "").trim();
            String namespace = function == null
                    ? clean(candidate.optString("namespace", null))
                    : clean(function.optString("namespace",
                            candidate.optString("namespace", null)));
            Definition definition = plan.find(name, namespace);
            if (definition == null) continue;
            if (Choice.FORCED.equals(plan.choice.mode)
                    && (!plan.choice.forcedName.equals(definition.name)
                    || !equalNamespace(plan.choice.forcedNamespace,
                            definition.namespace))) continue;
            Object arguments = function == null ? candidate.opt("arguments")
                    : function.opt("arguments");
            if (arguments == null || arguments == JSONObject.NULL) {
                if (CUSTOM.equals(definition.kind) && candidate.has("input")) {
                    arguments = new JSONObject();
                    try { ((JSONObject) arguments).put("input", candidate.opt("input")); }
                    catch (JSONException ignored) {}
                } else if (SHELL.equals(definition.kind) && candidate.has("action")) {
                    arguments = candidate.opt("action");
                } else if (APPLY_PATCH.equals(definition.kind) && candidate.has("operation")) {
                    arguments = candidate.opt("operation");
                }
            }
            String canonical = canonicalArguments(arguments, definition.kind);
            if (canonical == null || !validArguments(canonical, definition)) continue;
            // Stateful/action tools must complete before a dependent call can be planned. Keep
            // ordinary pure functions parallel-capable, but serialize shell/custom/patch actions.
            if (!FUNCTION.equals(definition.kind) && !calls.isEmpty()) break;
            calls.add(new Call(definition.kind, definition.namespace,
                    definition.name, canonical));
            if (!plan.parallel || !FUNCTION.equals(definition.kind)) break;
        }
        return calls.isEmpty() ? parseFallbackCalls(rawText, plan)
                : Collections.unmodifiableList(calls);
    }

    private static List<Call> parseFallbackCalls(String rawText, Plan plan) {
        List<Call> shorthand = parseShorthandCalls(rawText, plan);
        return shorthand.isEmpty() ? parseNarratedCall(rawText, plan) : shorthand;
    }

    /** Parse one or more line-oriented ToolName({json}) calls without trusting arbitrary text. */
    private static List<Call> parseShorthandCalls(String rawText, Plan plan) {
        if (rawText == null || plan == null || !plan.active()) return Collections.emptyList();
        Matcher matcher = SHORTHAND_CALL.matcher(rawText);
        ArrayList<Call> calls = new ArrayList<Call>();
        while (matcher.find()) {
            Definition definition = shorthandDefinition(plan, matcher.group(3));
            if (definition == null || !forcedChoiceAllows(plan, definition)) continue;
            int start = skipHorizontalWhitespace(rawText, matcher.end());
            if (start >= rawText.length() || rawText.charAt(start) != '{') continue;
            int end = balancedEnd(rawText, start);
            if (end <= start) continue;
            int closing = skipHorizontalWhitespace(rawText, end + 1);
            if (closing >= rawText.length() || rawText.charAt(closing) != ')') continue;
            Object arguments = parseJson(rawText.substring(start, end + 1));
            String canonical = canonicalArguments(arguments, definition.kind);
            if (canonical == null || !validArguments(canonical, definition)) continue;
            if (!FUNCTION.equals(definition.kind) && !calls.isEmpty()) break;
            calls.add(new Call(definition.kind, definition.namespace,
                    definition.name, canonical));
            if (!plan.parallel || !FUNCTION.equals(definition.kind)) break;
        }
        return calls.isEmpty() ? Collections.<Call>emptyList()
                : Collections.unmodifiableList(calls);
    }

    /** Earliest complete ToolName( prefix belonging to an available tool, or -1. */
    static int shorthandCallStart(String rawText, Plan plan) {
        if (rawText == null || plan == null || !plan.active()) return -1;
        Matcher matcher = SHORTHAND_CALL.matcher(rawText);
        while (matcher.find()) {
            Definition definition = shorthandDefinition(plan, matcher.group(3));
            if (definition != null && forcedChoiceAllows(plan, definition)) {
                return matcher.start(2);
            }
        }
        return -1;
    }

    /**
     * Earliest suffix on the current line that can still grow into ToolName(. This lets the SSE
     * redactor retain just "B"/"Ba"/"Bash" until the next token decides whether it is prose or a
     * private call, instead of leaking the name before the opening parenthesis arrives.
     */
    static int possibleShorthandCallStart(String rawText, Plan plan) {
        if (rawText == null || rawText.length() == 0 || plan == null || !plan.active()) return -1;
        int line = Math.max(rawText.lastIndexOf('\n'), rawText.lastIndexOf('\r')) + 1;
        int cursor = line;
        while (cursor < rawText.length()) {
            char ch = rawText.charAt(cursor);
            if (ch == ' ' || ch == '\t') cursor++;
            else break;
        }
        if (cursor + 1 < rawText.length()
                && (rawText.charAt(cursor) == '-' || rawText.charAt(cursor) == '*'
                || rawText.charAt(cursor) == '+')
                && Character.isWhitespace(rawText.charAt(cursor + 1))) {
            cursor += 2;
            while (cursor < rawText.length()
                    && (rawText.charAt(cursor) == ' ' || rawText.charAt(cursor) == '\t')) cursor++;
        }
        if (cursor < rawText.length() && rawText.charAt(cursor) == '`') cursor++;
        if (cursor >= rawText.length()) return -1;
        String suffix = rawText.substring(cursor);
        for (Definition definition : plan.tools) {
            if (!forcedChoiceAllows(plan, definition)) continue;
            for (String alias : shorthandAliases(definition)) {
                if (possibleAliasPrefix(suffix, alias)) return line;
            }
        }
        return -1;
    }

    static boolean resemblesCallSyntax(String rawText, Plan plan) {
        return resemblesEnvelope(rawText) || shorthandCallStart(rawText, plan) >= 0;
    }

    private static boolean possibleAliasPrefix(String suffix, String alias) {
        if (suffix == null || alias == null) return false;
        if (suffix.length() <= alias.length()) return alias.startsWith(suffix);
        if (!suffix.startsWith(alias)) return false;
        int cursor = alias.length();
        while (cursor < suffix.length()
                && (suffix.charAt(cursor) == ' ' || suffix.charAt(cursor) == '\t')) cursor++;
        return cursor == suffix.length() || suffix.charAt(cursor) == '(';
    }

    private static ArrayList<String> shorthandAliases(Definition definition) {
        ArrayList<String> aliases = new ArrayList<String>();
        aliases.add(definition.name);
        aliases.add("functions." + definition.name);
        if (definition.namespace != null) {
            aliases.add(definition.namespace + "." + definition.name);
            aliases.add(definition.namespace + ":" + definition.name);
            aliases.add(definition.namespace + "/" + definition.name);
            aliases.add(definition.namespace + "__" + definition.name);
        }
        return aliases;
    }

    private static Definition shorthandDefinition(Plan plan, String rawName) {
        if (plan == null) return null;
        String name = cleanTaggedName(rawName);
        return name == null ? null : plan.find(name, null);
    }

    private static boolean forcedChoiceAllows(Plan plan, Definition definition) {
        return plan != null && definition != null
                && (!Choice.FORCED.equals(plan.choice.mode)
                || (plan.choice.forcedName.equals(definition.name)
                && equalNamespace(plan.choice.forcedNamespace, definition.namespace)));
    }

    private static int skipHorizontalWhitespace(String value, int cursor) {
        while (cursor < value.length()) {
            char ch = value.charAt(cursor);
            if (ch == ' ' || ch == '\t') cursor++;
            else break;
        }
        return cursor;
    }

    static boolean resemblesEnvelope(String rawText) {
        if (rawText == null) return false;
        String lower = rawText.replace('▁', '_').replace('｜', '|')
                .toLowerCase(Locale.US);
        return lower.contains(ENVELOPE_KEY) || lower.contains("tool_calls")
                || lower.contains("<tool>") || lower.contains("<tool:")
                || lower.contains("<tool ") || lower.contains("<tool_call")
                || lower.contains("tool_call_begin")
                || lower.contains("<function_calls") || lower.contains("<invoke")
                || lower.contains("<function=") || lower.contains("assistant to=")
                || lower.contains("assistant recipient=")
                || Pattern.compile("(?m)^\\s*tool\\s+call\\s*:", Pattern.CASE_INSENSITIVE)
                        .matcher(rawText).find()
                || ((lower.contains("requested tool ") || lower.contains("request tool "))
                        && (lower.contains("with arguments") || lower.contains("with input")))
                || (lower.contains("[tool]") && lower.contains("tool result for"));
    }

    /** Stable identity used to suppress only an already-successful equivalent call. */
    static String signatureFor(String kind, String namespace, String name, Object arguments) {
        String toolName = clean(name);
        if (toolName == null) return null;
        String toolKind = clean(kind);
        if (toolKind == null) toolKind = FUNCTION;
        String canonical = canonicalArguments(arguments, toolKind);
        if (canonical == null) return null;
        try {
            Object parsed = new JSONObject(canonical);
            parsed = signatureArguments(toolKind, toolName, parsed);
            return toolKind + "\u0000" + String.valueOf(clean(namespace)) + "\u0000"
                    + toolName + "\u0000" + stableJson(parsed);
        } catch (Throwable ignored) {
            return toolKind + "\u0000" + String.valueOf(clean(namespace)) + "\u0000"
                    + toolName + "\u0000" + canonical;
        }
    }

    static String signatureFor(Call call) {
        return call == null ? null
                : signatureFor(call.kind, call.namespace, call.name, call.arguments);
    }

    /**
     * Read-only Claude Code tools may legitimately be called again after a mutation. Treating an
     * identical Read/Glob/Grep as a duplicate prevents the agent from verifying its own edit and
     * often makes it skip directly to another write. Unknown, namespaced, shell, and mutating
     * calls remain guarded because repeating them can have external side effects.
     */
    static boolean safeToRepeat(Call call) {
        return call != null && safeToRepeat(call.kind, call.namespace, call.name);
    }

    static boolean safeToRepeat(String kind, String namespace, String rawName) {
        if (!FUNCTION.equals(kind) || clean(namespace) != null) return false;
        String name = clean(rawName);
        if (name == null) return false;
        name = name.toLowerCase(Locale.US).replace("_", "").replace("-", "");
        return "read".equals(name) || "glob".equals(name) || "grep".equals(name)
                || "webfetch".equals(name) || "websearch".equals(name)
                || "taskoutput".equals(name) || "listmcpresources".equals(name)
                || "readmcpresource".equals(name);
    }

    private static Object signatureArguments(String kind, String name, Object parsed) {
        if (!(parsed instanceof JSONObject) || !FUNCTION.equals(kind)
                || !"bash".equalsIgnoreCase(name)) return parsed;
        JSONObject object = (JSONObject) parsed;
        Object command = object.opt("command");
        if (command == null || command == JSONObject.NULL
                || String.valueOf(command).trim().length() == 0) return parsed;
        try {
            // Claude Code may regenerate the same successful Bash action with only a changed
            // description or timeout. Those fields control presentation/waiting, not the shell
            // side effect; key the completion guard by the command so it is not executed twice.
            return new JSONObject().put("command", command);
        } catch (JSONException ignored) {
            return parsed;
        }
    }

    private static String stableJson(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.TreeSet<String> keys = new java.util.TreeSet<String>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (String key : keys) {
                if (!first) out.append(',');
                first = false;
                out.append(JSONObject.quote(key)).append(':')
                        .append(stableJson(object.opt(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(stableJson(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    private static Object parseEnvelope(String rawText) {
        String text = rawText.trim();
        Object direct = parseJson(text);
        if (direct != null) return direct;
        Object tagged = parseTaggedEnvelope(text);
        if (tagged != null) return tagged;
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                direct = parseJson(text.substring(firstLine + 1, closing).trim());
                if (direct != null) return direct;
            }
        }
        int marker = text.indexOf(ENVELOPE_KEY);
        if (marker < 0) marker = text.indexOf("\"tool_calls\"");
        if (marker < 0) marker = text.indexOf("tool_calls");
        if (marker >= 0) {
            for (int start = marker; start >= 0; start--) {
                if (text.charAt(start) != '{' && text.charAt(start) != '[') continue;
                int end = balancedEnd(text, start);
                if (end > marker) {
                    direct = parseJson(text.substring(start, end + 1));
                    if (direct != null) return direct;
                }
            }
        }
        // Tolerate a bare OpenAI-style single call embedded in short surrounding text.
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') continue;
            int end = balancedEnd(text, start);
            if (end < 0) continue;
            direct = parseJson(text.substring(start, end + 1));
            if (direct instanceof JSONObject) {
                JSONObject object = (JSONObject) direct;
                if (object.has("name") || object.has("function")) return direct;
            }
            start = Math.max(start, end);
        }
        return null;
    }

    private static Object parseTaggedEnvelope(String text) {
        JSONArray special = parseDeepSeekSpecialCalls(text);
        if (special.length() > 0) return envelope(special);

        JSONArray toolTags = new JSONArray();
        Matcher toolMatcher = Pattern.compile("(?is)<tool_call[^>]*>(.*?)</tool_call>")
                .matcher(text);
        while (toolMatcher.find()) {
            Object parsed = parseJson(toolMatcher.group(1).trim());
            appendParsedCalls(toolTags, parsed);
        }
        if (toolTags.length() > 0) return envelope(toolTags);

        JSONArray invokes = new JSONArray();
        Matcher invokeMatcher = Pattern.compile(
                "(?is)<invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"
                        + "(.*?)</invoke>").matcher(text);
        while (invokeMatcher.find()) {
            JSONObject arguments = new JSONObject();
            Matcher parameterMatcher = Pattern.compile(
                    "(?is)<parameter\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>"
                            + "(.*?)</parameter>").matcher(invokeMatcher.group(2));
            while (parameterMatcher.find()) {
                try {
                    arguments.put(parameterMatcher.group(1).trim(),
                            taggedParameterValue(xmlUnescape(parameterMatcher.group(2).trim())));
                } catch (JSONException ignored) {}
            }
            if (arguments.length() > 0) {
                appendCall(invokes, invokeMatcher.group(1), arguments);
            }
        }
        if (invokes.length() > 0) return envelope(invokes);

        JSONArray functionTags = new JSONArray();
        Matcher functionMatcher = Pattern.compile(
                "(?is)<function\\s*=\\s*([a-zA-Z0-9_.:-]+)\\s*>(.*?)</function>")
                .matcher(text);
        while (functionMatcher.find()) {
            Object arguments = parseJson(functionMatcher.group(2).trim());
            if (arguments instanceof JSONObject) {
                appendCall(functionTags, functionMatcher.group(1), arguments);
            }
        }
        if (functionTags.length() > 0) return envelope(functionTags);

        Matcher recipient = Pattern.compile(
                "(?is)assistant\\s+(?:to|recipient)\\s*=\\s*(?:functions\\.)?"
                        + "([a-zA-Z0-9_.:-]+)[^\\r\\n]*[\\r\\n]+")
                .matcher(text);
        if (recipient.find()) {
            int start = text.indexOf('{', recipient.end());
            if (start >= 0) {
                int end = balancedEnd(text, start);
                Object arguments = end > start
                        ? parseJson(text.substring(start, end + 1)) : null;
                if (arguments instanceof JSONObject) {
                    JSONArray calls = new JSONArray();
                    appendCall(calls, recipient.group(1), arguments);
                    if (calls.length() > 0) return envelope(calls);
                }
            }
        }
        return null;
    }

    private static JSONArray parseDeepSeekSpecialCalls(String text) {
        JSONArray calls = new JSONArray();
        Matcher matcher = Pattern.compile(
                "(?is)<[|｜]tool[ _▁]call[ _▁]begin[|｜]>(.*?)"
                        + "<[|｜]tool[ _▁]call[ _▁]argument[ _▁]begin[|｜]>(.*?)"
                        + "(?=<[|｜]tool[ _▁]call[ _▁]end[|｜]>)")
                .matcher(text);
        while (matcher.find()) {
            String name = cleanTaggedName(matcher.group(1));
            Object arguments = parseJson(matcher.group(2).trim());
            if (arguments instanceof JSONObject) appendCall(calls, name, arguments);
        }
        return calls;
    }

    private static void appendParsedCalls(JSONArray out, Object parsed) {
        if (parsed instanceof JSONArray) {
            JSONArray array = (JSONArray) parsed;
            for (int i = 0; i < array.length(); i++) {
                JSONObject value = array.optJSONObject(i);
                if (value != null) out.put(value);
            }
            return;
        }
        if (!(parsed instanceof JSONObject)) return;
        JSONObject object = (JSONObject) parsed;
        JSONArray nested = object.optJSONArray(ENVELOPE_KEY);
        if (nested == null) nested = object.optJSONArray("tool_calls");
        if (nested != null) {
            appendParsedCalls(out, nested);
        } else if (object.has("name") || object.has("function")) {
            out.put(object);
        }
    }

    private static void appendCall(JSONArray out, String rawName, Object arguments) {
        String name = cleanTaggedName(rawName);
        if (name == null || !(arguments instanceof JSONObject)) return;
        try {
            out.put(new JSONObject().put("name", name).put("arguments", arguments));
        } catch (JSONException ignored) {}
    }

    private static JSONObject envelope(JSONArray calls) {
        try { return new JSONObject().put(ENVELOPE_KEY, calls); }
        catch (JSONException impossible) { return new JSONObject(); }
    }

    private static String cleanTaggedName(String value) {
        String name = clean(value);
        if (name == null) return null;
        int newline = Math.max(name.lastIndexOf('\n'), name.lastIndexOf('\r'));
        if (newline >= 0) name = name.substring(newline + 1).trim();
        if (name.startsWith("functions.")) name = name.substring("functions.".length());
        int numericSuffix = name.lastIndexOf(':');
        if (numericSuffix > 0 && name.substring(numericSuffix + 1).matches("[0-9]+")) {
            name = name.substring(0, numericSuffix);
        }
        return clean(name);
    }

    private static Object taggedParameterValue(String value) {
        Object parsed = parseJson(value);
        if (parsed != null) return parsed;
        if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(value)) return JSONObject.NULL;
        try {
            if (value.matches("-?[0-9]+")) return Long.valueOf(value);
            if (value.matches("-?[0-9]+\\.[0-9]+")) return Double.valueOf(value);
        } catch (Throwable ignored) {}
        return value;
    }

    private static String xmlUnescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /**
     * Recover the first next action when DeepSeek narrates an Agent transcript instead of using
     * the requested JSON envelope. Only the first call is accepted: any following [tool] result
     * is model-fabricated text and must be ignored so the real client can execute the action.
     */
    private static List<Call> parseNarratedCall(String rawText, Plan plan) {
        if (rawText == null || plan == null) return Collections.emptyList();
        Matcher matcher = Pattern.compile(
                "(?is)\\brequest(?:ed)?\\s+tool\\s+([a-zA-Z0-9_.:/-]+)"
                        + "(?:\\s*\\([^\\n]*\\))?\\s+with\\s+"
                        + "(arguments|input)\\s*:\\s*")
                .matcher(rawText);
        if (!matcher.find()) return Collections.emptyList();
        Definition definition = plan.find(matcher.group(1), null);
        if (definition == null) return Collections.emptyList();
        if (Choice.FORCED.equals(plan.choice.mode)
                && (!plan.choice.forcedName.equals(definition.name)
                || !equalNamespace(plan.choice.forcedNamespace, definition.namespace))) {
            return Collections.emptyList();
        }

        String form = matcher.group(2).toLowerCase(Locale.US);
        if ("arguments".equals(form) || !CUSTOM.equals(definition.kind)) {
            int start = rawText.indexOf('{', matcher.end());
            if (start < 0) return Collections.emptyList();
            int end = balancedEnd(rawText, start);
            if (end <= start) return Collections.emptyList();
            Object arguments = parseJson(rawText.substring(start, end + 1));
            String canonical = canonicalArguments(arguments, definition.kind);
            if (canonical == null || !validArguments(canonical, definition)) {
                return Collections.emptyList();
            }
            return Collections.singletonList(new Call(definition.kind, definition.namespace,
                    definition.name, canonical));
        }

        String input = rawText.substring(matcher.end()).trim();
        if ("apply_patch".equals(definition.name)) {
            int end = input.indexOf("*** End Patch");
            if (end >= 0) input = input.substring(0, end + "*** End Patch".length());
        } else {
            int toolMarker = input.indexOf("\n[tool]");
            if (toolMarker >= 0) input = input.substring(0, toolMarker).trim();
        }
        if (input.length() == 0) return Collections.emptyList();
        try {
            String canonical = new JSONObject().put("input", input).toString();
            return Collections.singletonList(new Call(definition.kind, definition.namespace,
                    definition.name, canonical));
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    private static Object parseJson(String text) {
        if (text == null) return null;
        String value = text.trim();
        try {
            if (value.startsWith("{")) return new JSONObject(value);
            if (value.startsWith("[")) return new JSONArray(value);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int balancedEnd(String text, int start) {
        char opening = text.charAt(start);
        char closing = opening == '{' ? '}' : ']';
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == opening) {
                depth++;
            } else if (ch == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String canonicalArguments(Object value, String kind) {
        if (value instanceof JSONObject) return value.toString();
        if (value instanceof String) {
            String string = ((String) value).trim();
            try { return new JSONObject(string).toString(); }
            catch (Throwable ignored) {
                if (CUSTOM.equals(kind)) {
                    try { return new JSONObject().put("input", string).toString(); }
                    catch (JSONException impossible) { return null; }
                }
            }
        }
        if (CUSTOM.equals(kind) && value != null && value != JSONObject.NULL) {
            try { return new JSONObject().put("input", String.valueOf(value)).toString(); }
            catch (JSONException impossible) { return null; }
        }
        return null;
    }

    private static boolean validArguments(String canonical, Definition definition) {
        JSONObject object;
        try { object = new JSONObject(canonical); }
        catch (Throwable ignored) { return false; }
        if (CUSTOM.equals(definition.kind)) return object.has("input");
        if (SHELL.equals(definition.kind)) {
            JSONArray commands = object.optJSONArray("commands");
            if (commands != null && commands.length() > 0) {
                for (int i = 0; i < commands.length(); i++) {
                    if (!(commands.opt(i) instanceof String)
                            || commands.optString(i).trim().length() == 0) return false;
                }
                return true;
            }
            return object.optString("command", "").trim().length() > 0;
        }
        if (APPLY_PATCH.equals(definition.kind)) {
            String type = object.optString("type", "");
            if (!"create_file".equals(type) && !"update_file".equals(type)
                    && !"delete_file".equals(type)) return false;
            if (object.optString("path", "").trim().length() == 0) return false;
            return "delete_file".equals(type) || object.has("diff");
        }
        JSONArray required = definition.schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                String key = required.optString(i, "");
                if (key.length() > 0 && (!object.has(key) || object.isNull(key))) return false;
            }
        }
        return true;
    }

    private static void validateName(String name) throws LocalApiGateway.GatewayException {
        if (name == null || name.length() == 0 || name.length() > 128) {
            throw invalid("invalid_tool_name", "Tool name must contain 1 to 128 characters");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-' && ch != '.'
                    && ch != ':') {
                throw invalid("invalid_tool_name", "Unsupported character in tool name: " + name);
            }
        }
    }

    private static boolean isHostedTool(String kind) {
        return "web_search".equals(kind) || "web_search_preview".equals(kind)
                || "file_search".equals(kind) || "computer_use_preview".equals(kind)
                || "computer".equals(kind) || "code_interpreter".equals(kind)
                || "image_generation".equals(kind) || "tool_search".equals(kind)
                || "mcp".equals(kind);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.length() == 0 || "null".equals(cleaned) ? null : cleaned;
    }

    private static boolean equalNamespace(String left, String right) {
        String a = clean(left);
        String b = clean(right);
        return a == null ? b == null : a.equals(b);
    }

    private static JSONObject shellSchema() {
        try {
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("commands", new JSONObject().put("type", "array")
                                    .put("items", new JSONObject().put("type", "string")))
                            .put("timeout_ms", new JSONObject().put("type", "integer"))
                            .put("max_output_length", new JSONObject().put("type", "integer")))
                    .put("required", new JSONArray().put("commands"));
        } catch (JSONException impossible) { return new JSONObject(); }
    }

    private static JSONObject applyPatchSchema() {
        try {
            return new JSONObject().put("type", "object")
                    .put("properties", new JSONObject()
                            .put("type", new JSONObject().put("type", "string")
                                    .put("enum", new JSONArray().put("create_file")
                                            .put("update_file").put("delete_file")))
                            .put("path", new JSONObject().put("type", "string"))
                            .put("diff", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("type").put("path"));
        } catch (JSONException impossible) { return new JSONObject(); }
    }

    private static JSONObject cloneObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static LocalApiGateway.GatewayException invalid(String code, String message) {
        return new LocalApiGateway.GatewayException(400, code, "invalid_request_error", message);
    }
}
