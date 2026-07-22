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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android/Java adaptation of OmniRoute's MIT-licensed DeepSeek web tool bridge.
 *
 * <p>The preserved upstream sources are in {@code third_party/omniroute-tool-bridge}. This class
 * follows their strict {@code <tool>{json}</tool>} prompt, stack-based tag tokenizer, loose-JSON
 * normalization, conservative schema fallback, and unambiguous fuzzy tool-name matching. Keeping
 * the adapter isolated makes future upstream comparisons mechanical instead of growing another
 * private collection of response-shape regular expressions.</p>
 *
 * <p>Upstream: diegosouzapw/OmniRoute, commit
 * dffff5d656c169e41c4862cb38affbd9992f24a5.</p>
 */
final class OmniRouteToolBridge {
    private static final Pattern TAG_TOKEN_RE = Pattern.compile(
            "(?is)<(/?)(?:tool_call|tool)(:[A-Za-z0-9_.+\\-]+)?((?:\\s[^>]*)?)/?>");
    private static final Pattern BARE_TOOL_KEYS = Pattern.compile(
            "(?is)[{,]\\s*[\"']?(?:name|command)[\"']?\\s*:");
    private static final Pattern BARE_ARGUMENTS_KEY = Pattern.compile(
            "(?is)[{,]\\s*[\"']?arguments[\"']?\\s*:");
    private static final Pattern BARE_JSON_KEY = Pattern.compile(
            "([\\{,]\\s*)([A-Za-z_][A-Za-z0-9_-]*)(\\s*:)");

    private OmniRouteToolBridge() {}

    /** Strict prompt contract adapted from serializeDeepSeekToolPrompt(). */
    static String serializePrompt(OpenAiToolBridge.Plan plan) {
        if (plan == null || !plan.active()) return "";
        ArrayList<String> lines = new ArrayList<String>();
        for (OpenAiToolBridge.Definition tool : plan.tools) {
            StringBuilder line = new StringBuilder("- ").append(wireName(tool));
            if (tool.description.length() > 0) line.append(": ").append(tool.description);
            if (tool.schema.length() > 0) {
                line.append("\n  parameters: ").append(tool.schema.toString());
            }
            if (tool.format.length() > 0) {
                line.append("\n  format: ").append(tool.format.toString());
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        out.append("You can call tools. To call a tool, output ONLY this exact block ")
                .append("(no markdown fence):\n")
                .append("<tool>{\"name\": \"<tool_name>\", \"arguments\": { ... }}</tool>\n")
                .append("Rules:\n")
                .append("- Use exactly <tool>...</tool>. Do NOT use <tool:name>, <tool_call>, ")
                .append("<name>, <parameter>, id=/name= attributes, code fences, ")
                .append("ToolName({...}), or a textual 'Tool call:' label.\n")
                .append("- \"name\" must be one of the tools below; \"arguments\" must be a JSON object.\n")
                .append("- When a tool is needed, emit the <tool> block instead of only describing the plan.\n")
                .append("- Emit one <tool> block per call; several independent blocks may be back to back.\n")
                .append("- If no tool is needed, answer normally without any <tool> block.\n\n")
                .append("Available tools:\n");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    /**
     * Return request-scoped candidate calls in the shape consumed by OpenAiToolBridge's existing
     * schema and tool-choice validator. An empty array means the legacy compatibility parsers may
     * still try older native DeepSeek formats.
     */
    static JSONArray parseCandidates(String text, OpenAiToolBridge.Plan plan) {
        JSONArray out = new JSONArray();
        if (text == null || text.length() == 0 || plan == null || !plan.active()) return out;

        List<TagToken> tokens = tokenizeToolTags(text);
        if (tokens.isEmpty()) {
            parseBareJsonCandidates(text, plan, out);
            return out;
        }

        List<ToolBlock> blocks = pairToolBlocks(tokens, text.length());
        Collections.sort(blocks);
        for (ToolBlock block : blocks) {
            if (!isLeaf(block, blocks)) continue;
            String tagName = firstNonEmpty(block.open.suffix,
                    getAttr(block.open.attrs, "name"), getAttr(block.open.attrs, "id"));
            ExtractedCall call = extractCall(tagName,
                    text.substring(block.innerStart, block.innerEnd), plan);
            if (call != null) appendCandidate(out, call);
        }
        return out;
    }

    private static void parseBareJsonCandidates(
            String text, OpenAiToolBridge.Plan plan, JSONArray out) {
        int start = -1;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (depth == 0 && ch != '{') continue;
            if (escaped) {
                escaped = false;
                continue;
            }
            if (quote != 0) {
                if (ch == '\\') escaped = true;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}' && depth > 0 && --depth == 0 && start >= 0) {
                String raw = text.substring(start, i + 1);
                if (BARE_TOOL_KEYS.matcher(raw).find()
                        && BARE_ARGUMENTS_KEY.matcher(raw).find()) {
                    ExtractedCall call = extractCall("", raw, plan);
                    if (call != null) appendCandidate(out, call);
                }
                start = -1;
            }
        }
    }

    private static List<TagToken> tokenizeToolTags(String text) {
        ArrayList<TagToken> tokens = new ArrayList<TagToken>();
        Matcher matcher = TAG_TOKEN_RE.matcher(text);
        while (matcher.find()) {
            tokens.add(new TagToken(matcher.start(), matcher.end(),
                    "/".equals(matcher.group(1)),
                    matcher.group(2) == null ? "" : matcher.group(2).substring(1),
                    matcher.group(3) == null ? "" : matcher.group(3)));
        }
        return tokens;
    }

    private static List<ToolBlock> pairToolBlocks(List<TagToken> tokens, int textLength) {
        ArrayList<ToolBlock> blocks = new ArrayList<ToolBlock>();
        ArrayList<TagToken> stack = new ArrayList<TagToken>();
        for (TagToken token : tokens) {
            if (!token.closing) {
                stack.add(token);
                continue;
            }
            if (stack.isEmpty()) continue;
            TagToken open = stack.remove(stack.size() - 1);
            blocks.add(new ToolBlock(open, token, open.end, token.start));
        }
        for (TagToken open : stack) {
            TagToken synthetic = new TagToken(textLength, textLength, true, "", "");
            blocks.add(new ToolBlock(open, synthetic, open.end, textLength));
        }
        return blocks;
    }

    private static boolean isLeaf(ToolBlock candidate, List<ToolBlock> blocks) {
        for (ToolBlock other : blocks) {
            if (other == candidate) continue;
            if (other.open.start >= candidate.innerStart
                    && other.close.end <= candidate.innerEnd) return false;
        }
        return true;
    }

    private static ExtractedCall extractCall(
            String rawTagName, String rawInner, OpenAiToolBridge.Plan plan) {
        String inner = rawInner == null ? "" : rawInner.trim();
        String nameChild = getXmlChild(inner, "name");
        String argsChild = firstNonEmpty(getXmlChild(inner, "arguments"),
                getXmlChild(inner, "parameters"));
        JSONObject parameterObject = argsChild.length() > 0
                ? null : buildArgsFromParameters(inner);
        boolean hasXmlChildren = nameChild.length() > 0 || argsChild.length() > 0
                || parameterObject != null;
        JSONObject json = hasXmlChildren ? null : parseLooseJsonObject(inner);
        String jsonName = json == null ? ""
                : firstNonEmpty(stringValue(json.opt("name")), stringValue(json.opt("type")));

        OpenAiToolBridge.Definition definition = null;
        boolean nameFromTag = false;
        if (nameChild.length() > 0) definition = resolveDefinition(nameChild, plan);
        if (definition == null && jsonName.length() > 0) {
            definition = resolveDefinition(jsonName, plan);
        }
        if (definition == null && rawTagName != null && rawTagName.length() > 0) {
            definition = resolveDefinition(rawTagName, plan);
            nameFromTag = definition != null;
        }
        if (definition == null && rawTagName.length() == 0 && json != null) {
            String command = stringValue(json.opt("command"));
            if (command.length() > 0) definition = resolveDefinition(command, plan);
        }
        if (definition == null && parameterObject != null) {
            definition = resolveBySchema(parameterObject, plan);
        }
        if (definition == null) return null;

        Object arguments;
        if (argsChild.length() > 0) {
            JSONObject parsed = parseLooseJsonObject(argsChild);
            arguments = parsed == null ? argsChild : parsed;
        } else if (parameterObject != null) {
            arguments = parameterObject;
        } else if (json != null) {
            if (json.has("arguments")) arguments = json.opt("arguments");
            else if (json.has("params")) arguments = json.opt("params");
            else if (nameFromTag) arguments = cloneObject(json);
            else {
                JSONObject rest = cloneObject(json);
                rest.remove("name");
                rest.remove("type");
                rest.remove("id");
                rest.remove("command");
                rest.remove("arguments");
                rest.remove("params");
                arguments = rest;
            }
        } else {
            arguments = new JSONObject();
        }
        return new ExtractedCall(definition, arguments);
    }

    private static JSONObject buildArgsFromParameters(String inner) {
        JSONObject out = new JSONObject();
        boolean found = false;
        Matcher matcher = Pattern.compile("(?is)<parameter\\b([^>]*)>").matcher(inner);
        while (matcher.find()) {
            String attrs = matcher.group(1) == null ? "" : matcher.group(1);
            String name = getAttr(attrs, "name");
            if (name.length() == 0) continue;
            String value = getAttr(attrs, "content");
            if (value.length() == 0) {
                int close = indexOfIgnoreCase(inner, "</parameter>", matcher.end());
                int next = indexOfIgnoreCase(inner, "<parameter", matcher.end());
                if (close >= 0 && (next < 0 || close < next)) {
                    value = inner.substring(matcher.end(), close).trim();
                }
            }
            try {
                out.put(name, xmlUnescape(value));
                found = true;
            } catch (JSONException ignored) {}
        }
        return found ? out : null;
    }

    private static OpenAiToolBridge.Definition resolveBySchema(
            JSONObject arguments, OpenAiToolBridge.Plan plan) {
        Set<String> extracted = jsonKeys(arguments);
        if (extracted.isEmpty()) return null;
        OpenAiToolBridge.Definition match = null;
        for (OpenAiToolBridge.Definition definition : plan.tools) {
            JSONObject properties = definition.schema.optJSONObject("properties");
            Set<String> schemaKeys = jsonKeys(properties);
            if (schemaKeys.isEmpty() || !schemaKeys.containsAll(extracted)) continue;
            if (match != null && match != definition) return null;
            match = definition;
        }
        return match;
    }

    private static OpenAiToolBridge.Definition resolveDefinition(
            String emitted, OpenAiToolBridge.Plan plan) {
        if (emitted == null || emitted.trim().length() == 0) return null;
        String wanted = emitted.trim();
        OpenAiToolBridge.Definition best = null;
        double bestScore = 0d;
        double secondScore = 0d;
        for (OpenAiToolBridge.Definition definition : plan.tools) {
            double score = Math.max(scoreToolName(wanted, definition.name),
                    scoreToolName(wanted, wireName(definition)));
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = definition;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        if (best == null || bestScore < 0.72d) return null;
        if (bestScore < 0.98d && bestScore - secondScore < 0.08d) return null;
        return best;
    }

    private static double scoreToolName(String emitted, String requested) {
        if (emitted.equals(requested)) return 1d;
        String left = normalizeToolName(emitted);
        String right = normalizeToolName(requested);
        if (left.length() == 0 || right.length() == 0) return 0d;
        if (left.equals(right)) return 0.98d;
        int shorter = Math.min(left.length(), right.length());
        int longer = Math.max(left.length(), right.length());
        if (shorter >= 4 && (left.contains(right) || right.contains(left))) {
            return 0.86d - ((double) (longer - shorter) / Math.max(longer, 1) / 4d);
        }
        int distance = levenshteinDistance(left, right);
        double similarity = 1d - ((double) distance / Math.max(longer, 1));
        return similarity >= 0.72d ? similarity : 0d;
    }

    private static int levenshteinDistance(String left, String right) {
        if (left.equals(right)) return 0;
        if (left.length() == 0) return right.length();
        if (right.length() == 0) return left.length();
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(current[j - 1] + 1,
                        Math.min(previous[j] + 1, previous[j - 1] + cost));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String normalizeToolName(String value) {
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static JSONObject parseLooseJsonObject(String raw) {
        if (raw == null) return null;
        String stripped = stripCodeFence(raw);
        JSONObject parsed = parseObject(stripped);
        return parsed != null ? parsed : parseObject(normalizeLooseJson(stripped));
    }

    private static JSONObject parseObject(String value) {
        try { return new JSONObject(value); }
        catch (Throwable ignored) { return null; }
    }

    private static String stripCodeFence(String value) {
        return value.trim()
                .replaceFirst("(?is)^```(?:json|javascript|js|python)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "").trim();
    }

    private static String normalizeLooseJson(String value) {
        String normalized = replacePythonLiterals(convertSingleQuotedStrings(value));
        Matcher matcher = BARE_JSON_KEY.matcher(normalized);
        StringBuffer quoted = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(quoted, Matcher.quoteReplacement(
                    matcher.group(1) + "\"" + matcher.group(2) + "\"" + matcher.group(3)));
        }
        matcher.appendTail(quoted);
        return quoted.toString().replaceAll(",\\s*([}\\]])", "$1");
    }

    private static String convertSingleQuotedStrings(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                if (ch == '"' && inSingle) out.append('\\');
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                out.append(ch);
                escaped = true;
            } else if (ch == '"') {
                if (inSingle) out.append("\\\"");
                else {
                    inDouble = !inDouble;
                    out.append(ch);
                }
            } else if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append('"');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String replacePythonLiterals(String value) {
        StringBuilder out = new StringBuilder(value.length());
        StringBuilder token = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                flushPythonToken(out, token);
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                flushPythonToken(out, token);
                out.append(ch);
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                flushPythonToken(out, token);
                inString = !inString;
                out.append(ch);
                continue;
            }
            if (!inString && Character.isLetter(ch)) token.append(ch);
            else {
                flushPythonToken(out, token);
                out.append(ch);
            }
        }
        flushPythonToken(out, token);
        return out.toString();
    }

    private static void flushPythonToken(StringBuilder out, StringBuilder token) {
        if (token.length() == 0) return;
        String value = token.toString();
        if ("True".equals(value)) out.append("true");
        else if ("False".equals(value)) out.append("false");
        else if ("None".equals(value)) out.append("null");
        else out.append(value);
        token.setLength(0);
    }

    private static String getAttr(String attrs, String name) {
        if (attrs == null || attrs.length() == 0) return "";
        Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(name)
                + "\\s*=\\s*([\"'])").matcher(attrs);
        if (!matcher.find()) return "";
        char quote = matcher.group(1).charAt(0);
        StringBuilder out = new StringBuilder();
        for (int i = matcher.end(); i < attrs.length(); i++) {
            char ch = attrs.charAt(i);
            if (ch == '\\' && i + 1 < attrs.length()) out.append(attrs.charAt(++i));
            else if (ch == quote) break;
            else out.append(ch);
        }
        return out.toString();
    }

    private static String getXmlChild(String inner, String tag) {
        Matcher matcher = Pattern.compile("(?is)<" + Pattern.quote(tag)
                + "\\b[^>]*>(.*?)</" + Pattern.quote(tag) + ">").matcher(inner);
        return matcher.find() ? xmlUnescape(matcher.group(1).trim()) : "";
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        return text.toLowerCase(Locale.US).indexOf(needle.toLowerCase(Locale.US), from);
    }

    private static String xmlUnescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String stringValue(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && value.length() > 0) return value;
        }
        return "";
    }

    private static String wireName(OpenAiToolBridge.Definition definition) {
        return definition.namespace == null ? definition.name
                : definition.namespace + "." + definition.name;
    }

    private static JSONObject cloneObject(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    private static Set<String> jsonKeys(JSONObject object) {
        if (object == null) return Collections.emptySet();
        HashSet<String> keys = new HashSet<String>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    private static void appendCandidate(JSONArray out, ExtractedCall call) {
        try {
            JSONObject candidate = new JSONObject().put("name", call.definition.name)
                    .put("arguments", call.arguments == null
                            ? new JSONObject() : call.arguments);
            if (call.definition.namespace != null) {
                candidate.put("namespace", call.definition.namespace);
            }
            out.put(candidate);
        } catch (JSONException ignored) {}
    }

    private static final class ExtractedCall {
        final OpenAiToolBridge.Definition definition;
        final Object arguments;

        ExtractedCall(OpenAiToolBridge.Definition definition, Object arguments) {
            this.definition = definition;
            this.arguments = arguments;
        }
    }

    private static final class TagToken {
        final int start;
        final int end;
        final boolean closing;
        final String suffix;
        final String attrs;

        TagToken(int start, int end, boolean closing, String suffix, String attrs) {
            this.start = start;
            this.end = end;
            this.closing = closing;
            this.suffix = suffix;
            this.attrs = attrs;
        }
    }

    private static final class ToolBlock implements Comparable<ToolBlock> {
        final TagToken open;
        final TagToken close;
        final int innerStart;
        final int innerEnd;

        ToolBlock(TagToken open, TagToken close, int innerStart, int innerEnd) {
            this.open = open;
            this.close = close;
            this.innerStart = innerStart;
            this.innerEnd = innerEnd;
        }

        @Override public int compareTo(ToolBlock other) {
            return open.start < other.open.start ? -1 : (open.start == other.open.start ? 0 : 1);
        }
    }
}
