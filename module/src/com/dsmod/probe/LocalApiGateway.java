package com.dsmod.probe;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small authenticated OpenAI/Anthropic-compatible HTTP gateway hosted in the injected DeepSeek
 * process.
 * It deliberately owns no DeepSeek credentials: the Backend invokes the already authenticated
 * host transport and this class only translates local HTTP/JSON/SSE.
 */
final class LocalApiGateway {
    static final String INFO_FILE = "/data/data/com.deepseek.chat/files/deekseep_local_api.txt";
    static final String LOG_FILE = "/data/data/com.deepseek.chat/files/deekseep_api.log";
    static final String STATUS_FILE = "/data/data/com.deepseek.chat/files/deekseep_api_status.json";
    static final String PUBLIC_INFO_FILE = "/storage/emulated/0/Deekseep_API.txt";

    private static final int DEFAULT_PORT = 8765;
    private static final int MAX_PORT_TRIES = 16;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int SOCKET_READ_TIMEOUT_MS = 30_000;
    private static final long COMPLETION_REQUEST_BUDGET_MS = 170_000L;
    private static final int MAX_RESPONSE_STATES = 128;
    private static final long RESPONSE_STATE_TTL_MS = 6L * 60L * 60L * 1000L;
    static final String PROTOCOL_OPENAI = "openai";
    static final String PROTOCOL_ANTHROPIC = "anthropic";
    private static final String PROTOCOL_FILE_NAME = "deekseep_local_api_protocol";
    private static final long SSE_HEARTBEAT_MS = 5_000L;
    private static final Object LOCK = new Object();

    interface Backend {
        boolean isReady();
        String readinessDetail();
        CompletionResult complete(CompletionRequest request, DeltaSink sink) throws Exception;
    }

    interface DeltaSink {
        /**
         * Called only after the native completion Flow has been entered.  Protocol adapters use
         * this boundary to avoid publishing a synthetic "thinking" state while the request is
         * still queued, minting PoW, or waiting for an upstream connection.
         */
        default void onUpstreamStarted() throws Exception {}
        /** Return false when the client connection is gone and upstream collection should stop. */
        boolean onText(String delta) throws Exception;
        boolean onReasoning(String delta) throws Exception;
        boolean isCancelled();
        /** True once a complete structured action has been captured; not a client cancellation. */
        default boolean isSatisfied() { return false; }
    }

    static final class CompletionRequest {
        final String requestId;
        final String requestedModel;
        final String nativeModel;
        /** Conversation without the request-scoped tool protocol, used for continuation state. */
        final String basePrompt;
        final String prompt;
        final boolean reasoning;
        final boolean search;
        final int maxOutputTokens;
        final OpenAiToolBridge.Plan toolPlan;
        final String previousResponseId;
        final boolean responsesApi;
        /** Opaque, hashed client conversation identity (for example Claude Code's session UUID). */
        final String clientSessionScope;
        /** Canonical OpenAI Responses text.format requested by the caller, or null for text. */
        final JSONObject outputTextFormat;
        /** Shared by an initial generation and its optional tool-format repair generation. */
        final long deadlineAtMs;
        /** Structured calls already completed successfully in the supplied conversation. */
        final Map<String, String> knownToolCalls;
        final Set<String> completedToolCalls;
        /** Read-only calls that are valid again after a new instruction or intervening mutation. */
        final Set<String> repeatableCompletedToolCalls;

        CompletionRequest(String requestId, String requestedModel, String nativeModel,
                          String basePrompt, String prompt, boolean reasoning, boolean search,
                          int maxOutputTokens, OpenAiToolBridge.Plan toolPlan,
                          String previousResponseId, boolean responsesApi) {
            this(requestId, requestedModel, nativeModel, basePrompt, prompt, reasoning, search,
                    maxOutputTokens, toolPlan, previousResponseId, responsesApi,
                    System.currentTimeMillis() + COMPLETION_REQUEST_BUDGET_MS,
                    Collections.<String, String>emptyMap(),
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet(), null, null);
        }

        private CompletionRequest(String requestId, String requestedModel, String nativeModel,
                          String basePrompt, String prompt, boolean reasoning, boolean search,
                          int maxOutputTokens, OpenAiToolBridge.Plan toolPlan,
                          String previousResponseId, boolean responsesApi, long deadlineAtMs,
                          Map<String, String> knownToolCalls,
                          Set<String> completedToolCalls,
                          Set<String> repeatableCompletedToolCalls,
                          String clientSessionScope, JSONObject outputTextFormat) {
            this.requestId = requestId;
            this.requestedModel = requestedModel;
            this.nativeModel = nativeModel;
            this.basePrompt = basePrompt;
            this.prompt = prompt;
            this.reasoning = reasoning;
            this.search = search;
            this.maxOutputTokens = maxOutputTokens;
            this.toolPlan = toolPlan;
            this.previousResponseId = previousResponseId;
            this.responsesApi = responsesApi;
            this.clientSessionScope = clientSessionScope;
            this.outputTextFormat = outputTextFormat;
            this.deadlineAtMs = deadlineAtMs;
            this.knownToolCalls = knownToolCalls == null || knownToolCalls.isEmpty()
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new HashMap<String, String>(knownToolCalls));
            this.completedToolCalls = completedToolCalls == null || completedToolCalls.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new HashSet<String>(completedToolCalls));
            this.repeatableCompletedToolCalls = repeatableCompletedToolCalls == null
                    || repeatableCompletedToolCalls.isEmpty()
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(
                            new HashSet<String>(repeatableCompletedToolCalls));
        }

        CompletionRequest withPrompt(String replacement) {
            return new CompletionRequest(requestId, requestedModel, nativeModel, basePrompt,
                    replacement, reasoning, search, maxOutputTokens, toolPlan,
                    previousResponseId, responsesApi, deadlineAtMs,
                    knownToolCalls, completedToolCalls, repeatableCompletedToolCalls,
                    clientSessionScope, outputTextFormat);
        }

        CompletionRequest withToolHistory(ToolHistory history) {
            if (history == null) return this;
            return new CompletionRequest(requestId, requestedModel, nativeModel, basePrompt,
                    prompt, reasoning, search, maxOutputTokens, toolPlan,
                    previousResponseId, responsesApi, deadlineAtMs,
                    history.knownCalls, history.completedCalls, history.repeatableCalls,
                    clientSessionScope, outputTextFormat);
        }

        CompletionRequest withClientSessionScope(String scope) {
            String normalized = scope == null || scope.length() == 0 ? null : scope;
            if (normalized == null ? clientSessionScope == null
                    : normalized.equals(clientSessionScope)) return this;
            return new CompletionRequest(requestId, requestedModel, nativeModel, basePrompt,
                    prompt, reasoning, search, maxOutputTokens, toolPlan,
                    previousResponseId, responsesApi, deadlineAtMs,
                    knownToolCalls, completedToolCalls, repeatableCompletedToolCalls,
                    normalized, outputTextFormat);
        }

        CompletionRequest withOutputTextFormat(JSONObject format) {
            return new CompletionRequest(requestId, requestedModel, nativeModel, basePrompt,
                    prompt, reasoning, search, maxOutputTokens, toolPlan,
                    previousResponseId, responsesApi, deadlineAtMs,
                    knownToolCalls, completedToolCalls, repeatableCompletedToolCalls,
                    clientSessionScope, format);
        }

        boolean toolsActive() {
            return toolPlan != null && toolPlan.active();
        }

        boolean auxiliary() {
            if (requestedModel == null) return false;
            String lower = requestedModel.toLowerCase(Locale.US);
            return lower.equals("deepseek-aux") || lower.startsWith("deepseek-aux-");
        }

        /** Historical UI/main-turn classifier retained for compatibility tests. */
        boolean interactiveAgent() {
            return toolsActive() && !auxiliary();
        }

        /** Main Claude turn or a tool-bearing Task/subagent turn. */
        boolean agentic() {
            return toolsActive();
        }
    }

    static final class CompletionResult {
        final String text;
        final String reasoning;
        final String finishReason;
        final List<OpenAiToolBridge.Call> toolCalls;

        CompletionResult(String text, String reasoning, String finishReason) {
            this(text, reasoning, finishReason, Collections.<OpenAiToolBridge.Call>emptyList());
        }

        CompletionResult(String text, String reasoning, String finishReason,
                         List<OpenAiToolBridge.Call> toolCalls) {
            this.text = text == null ? "" : text;
            this.reasoning = reasoning == null ? "" : reasoning;
            this.finishReason = finishReason == null ? "stop" : finishReason;
            this.toolCalls = toolCalls == null
                    ? Collections.<OpenAiToolBridge.Call>emptyList() : toolCalls;
        }

        boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    static class GatewayException extends Exception {
        final int status;
        final String code;
        final String type;

        GatewayException(int status, String code, String message) {
            this(status, code, "invalid_request_error", message);
        }

        GatewayException(int status, String code, String type, String message) {
            super(message);
            this.status = status;
            this.code = code;
            this.type = type;
        }
    }

    private static final class ResponseState {
        final String transcript;
        final long createdAt;
        final Map<String, String> knownToolCalls;
        final Set<String> completedToolCalls;

        ResponseState(String transcript, long createdAt,
                      Map<String, String> knownToolCalls,
                      Set<String> completedToolCalls) {
            this.transcript = transcript;
            this.createdAt = createdAt;
            this.knownToolCalls = knownToolCalls == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new HashMap<String, String>(knownToolCalls));
            this.completedToolCalls = completedToolCalls == null
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new HashSet<String>(completedToolCalls));
        }
    }

    private static final class ToolHistory {
        final Map<String, String> knownCalls = new HashMap<String, String>();
        final Set<String> completedCalls = new HashSet<String>();
        final Set<String> repeatableCalls = new HashSet<String>();

        ToolHistory() {}

        ToolHistory(ResponseState state) {
            if (state == null) return;
            knownCalls.putAll(state.knownToolCalls);
            completedCalls.addAll(state.completedToolCalls);
        }
    }

    private static volatile Backend backend;
    private static volatile ServerSocket serverSocket;
    private static volatile Thread acceptThread;
    private static volatile ThreadPoolExecutor workers;
    private static volatile boolean running;
    private static volatile int port;
    private static volatile String apiKey;
    private static volatile String protocolMode = PROTOCOL_OPENAI;
    private static volatile Context appContext;
    private static final AtomicLong receivedRequests = new AtomicLong();
    private static final AtomicLong successfulRequests = new AtomicLong();
    private static final AtomicLong failedRequests = new AtomicLong();
    private static final AtomicLong cancelledRequests = new AtomicLong();
    private static final AtomicLong emittedToolCalls = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> failureReasons =
            new ConcurrentHashMap<String, AtomicLong>();
    private static volatile long lastRequestAt;
    private static volatile long lastSuccessAt;
    private static volatile long lastFailureAt;
    private static volatile String lastRoute = "尚未收到请求";
    private static volatile String lastFailure = "无";
    private static final ConcurrentHashMap<String, ResponseState> RESPONSE_STATES =
            new ConcurrentHashMap<String, ResponseState>();

    private LocalApiGateway() {}

    static void start(Context context, Backend newBackend) {
        if (context == null || newBackend == null) return;
        synchronized (LOCK) {
            backend = newBackend;
            appContext = context.getApplicationContext();
            if (running && serverSocket != null && !serverSocket.isClosed()) return;
            apiKey = loadOrCreateKey(appContext);
            protocolMode = loadProtocolMode(appContext);
            try {
                ServerSocket server = bindServer();
                serverSocket = server;
                running = true;
                // Keep health/config routes responsive while completion workers wait in the
                // serialized native lane. Native calls themselves are still protected by Main.
                // A queued HTTP socket cannot receive message_start/thinking yet. Start a small
                // bounded thread per accepted stream and let Main's native lane perform the real
                // fair scheduling; this keeps every admitted Claude request visibly alive.
                workers = new ThreadPoolExecutor(4, 24, 30L, TimeUnit.SECONDS,
                        new SynchronousQueue<Runnable>(),
                        new ThreadPoolExecutor.AbortPolicy());
                workers.allowCoreThreadTimeOut(true);
                acceptThread = new Thread(new Runnable() {
                    @Override public void run() { acceptLoop(); }
                }, "Deekseep-API-Accept");
                acceptThread.setDaemon(true);
                acceptThread.start();
                writeConnectionInfo(true, null);
                apiLog("START endpoint=" + endpoint() + " protocol=" + protocolMode
                        + " key_configured=" + (apiKey != null)
                        + " backend_ready=" + newBackend.isReady());
                writeRuntimeState();
            } catch (Throwable t) {
                running = false;
                closeQuietly(serverSocket);
                serverSocket = null;
                writeConnectionInfo(false, String.valueOf(t));
                apiLog("START_FAILED " + safeMessage(t));
                recordFailure("gateway start", "start_failed: " + safeMessage(t));
            }
        }
    }

    static void stop() {
        synchronized (LOCK) {
            running = false;
            closeQuietly(serverSocket);
            serverSocket = null;
            ThreadPoolExecutor pool = workers;
            workers = null;
            if (pool != null) pool.shutdownNow();
            RESPONSE_STATES.clear();
            writeConnectionInfo(false, "disabled");
            apiLog("STOP");
            writeRuntimeState();
        }
    }

    static boolean isRunning() {
        ServerSocket server = serverSocket;
        return running && server != null && !server.isClosed();
    }

    static String endpoint() {
        return PROTOCOL_ANTHROPIC.equals(protocolMode) ? rootEndpoint() : openAiEndpoint();
    }

    static String rootEndpoint() {
        int p = port > 0 ? port : DEFAULT_PORT;
        return "http://127.0.0.1:" + p;
    }

    static String lanRootEndpoint() {
        String address = firstLanAddress();
        return address == null ? null : "http://" + address + ":"
                + (port > 0 ? port : DEFAULT_PORT);
    }

    static String lanEndpoint() {
        String root = lanRootEndpoint();
        if (root == null) return null;
        return PROTOCOL_ANTHROPIC.equals(protocolMode) ? root : root + "/v1";
    }

    static String openAiEndpoint() {
        return rootEndpoint() + "/v1";
    }

    static String protocolMode() {
        return PROTOCOL_ANTHROPIC.equals(protocolMode)
                ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    static void setProtocolMode(Context context, String requested) {
        synchronized (LOCK) {
            String normalized = PROTOCOL_ANTHROPIC.equalsIgnoreCase(requested)
                    ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
            protocolMode = normalized;
            Context c = context == null ? appContext : context.getApplicationContext();
            if (c != null) {
                writePrivateText(new File(c.getFilesDir(), PROTOCOL_FILE_NAME), normalized);
            }
            writeConnectionInfo(isRunning(), "protocol changed");
            apiLog("PROTOCOL_CHANGED protocol=" + normalized + " endpoint=" + endpoint());
            writeRuntimeState();
        }
    }

    static String apiKey() {
        return apiKey == null ? "" : apiKey;
    }

    static String connectionInfo() {
        Backend b = backend;
        String state = isRunning() ? "运行中" : "未运行";
        String ready = b == null ? "后端未安装"
                : b.readinessDetail();
        String protocol = PROTOCOL_ANTHROPIC.equals(protocolMode) ? "Anthropic" : "OpenAI";
        String lan = lanEndpoint();
        return "状态：" + state + "（" + ready + "）\n当前格式：" + protocol
                + "\n本机地址：" + endpoint()
                + (lan == null ? "\n局域网地址：连接 Wi-Fi 后自动显示"
                        : "\n局域网地址：" + lan)
                + "\nAPI 密钥：" + (apiKey == null ? "启动后生成" : apiKey);
    }

    static String rotateKey(Context context) {
        synchronized (LOCK) {
            Context c = context == null ? appContext : context.getApplicationContext();
            if (c == null) return null;
            apiKey = generateKey();
            writePrivateText(new File(c.getFilesDir(), "deekseep_local_api_key"), apiKey);
            writeConnectionInfo(isRunning(), "key rotated");
            apiLog("KEY_ROTATED endpoint=" + endpoint());
            writeRuntimeState();
            return apiKey;
        }
    }

    /** @return null on success, otherwise a user-facing validation error. */
    static String setCustomKey(Context context, String candidate) {
        synchronized (LOCK) {
            Context c = context == null ? appContext : context.getApplicationContext();
            if (c == null) return "DeepSeek 上下文尚未就绪";
            String value = candidate == null ? "" : candidate.trim();
            if (value.length() < 8 || value.length() > 256) {
                return "API Key 长度必须为 8 到 256 个字符";
            }
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch <= 0x20 || ch >= 0x7f) {
                    return "API Key 只能包含不带空格的可打印 ASCII 字符";
                }
            }
            apiKey = value;
            writePrivateText(new File(c.getFilesDir(), "deekseep_local_api_key"), value);
            writeConnectionInfo(isRunning(), "custom key saved");
            apiLog("CUSTOM_KEY_SAVED endpoint=" + endpoint());
            writeRuntimeState();
            return null;
        }
    }

    static String runtimeStatus() {
        Backend b = backend;
        ThreadPoolExecutor pool = workers;
        StringBuilder reasons = new StringBuilder();
        for (Map.Entry<String, AtomicLong> entry : failureReasons.entrySet()) {
            if (reasons.length() > 0) reasons.append('\n');
            reasons.append("• ").append(entry.getKey()).append(" × ")
                    .append(entry.getValue().get());
        }
        if (reasons.length() == 0) reasons.append("无");
        return "监听状态：" + (isRunning() ? "运行中" : "已停止")
                + "\n协议格式：" + (PROTOCOL_ANTHROPIC.equals(protocolMode)
                        ? "Anthropic Messages" : "OpenAI Chat / Responses")
                + "\n原生后端：" + (b == null ? "未安装" : b.readinessDetail())
                + "\n收到请求：" + receivedRequests.get()
                + "\n成功请求：" + successfulRequests.get()
                + "\n失败请求：" + failedRequests.get()
                + "\n客户端中断：" + cancelledRequests.get()
                + "\n已返回工具调用：" + emittedToolCalls.get()
                + "\nResponses 延续状态：" + RESPONSE_STATES.size()
                + "\nHTTP 工作线程：" + (pool == null ? 0 : pool.getActiveCount())
                + "（排队 " + (pool == null ? 0 : pool.getQueue().size()) + "）"
                + "\n局域网入口：" + (lanEndpoint() == null ? "未检测到 Wi-Fi 地址"
                        : lanEndpoint())
                + "\n最近路由：" + lastRoute
                + "\n最近失败：" + lastFailure
                + "\n失败原因统计：\n" + reasons
                + "\n详细日志：" + LOG_FILE;
    }

    /** Keep native transport diagnostics beside the HTTP log for one-stop debugging. */
    static void diagnostic(String message) {
        apiLog(message == null ? "NATIVE_DIAGNOSTIC empty" : message);
    }

    private static ServerSocket bindServer() throws IOException {
        IOException last = null;
        for (int i = 0; i < MAX_PORT_TRIES; i++) {
            int candidate = DEFAULT_PORT + i;
            ServerSocket server = new ServerSocket();
            try {
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(
                        InetAddress.getByName("0.0.0.0"), candidate), 32);
                port = candidate;
                return server;
            } catch (IOException e) {
                last = e;
                closeQuietly(server);
            }
        }
        throw last == null ? new IOException("no local API port available") : last;
    }

    private static void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                ServerSocket server = serverSocket;
                if (server == null) break;
                socket = server.accept();
                socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                final Socket accepted = socket;
                ThreadPoolExecutor pool = workers;
                if (pool == null) {
                    closeQuietly(accepted);
                    continue;
                }
                try {
                    pool.execute(new Runnable() {
                        @Override public void run() { handleSocket(accepted); }
                    });
                } catch (Throwable rejected) {
                    writeBusyAndClose(accepted);
                }
            } catch (SocketException e) {
                if (running) apiLog("ACCEPT_SOCKET_ERROR " + safeMessage(e));
            } catch (Throwable t) {
                if (running) apiLog("ACCEPT_ERROR " + safeMessage(t));
                closeQuietly(socket);
            }
        }
    }

    private static void handleSocket(Socket socket) {
        String requestTag = "unknown";
        boolean anthropicRequest = false;
        long started = System.currentTimeMillis();
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            HttpRequest http = readRequest(in);
            recordReceived();
            requestTag = http.method + " " + http.path;
            anthropicRequest = http.path.startsWith("/v1/messages");
            lastRoute = requestTag;
            apiLog("REQUEST_BEGIN route=" + requestTag + " bytes=" + http.body.length);

            if ("OPTIONS".equals(http.method)) {
                writeEmpty(out, 204, "No Content");
                recordSuccess(requestTag);
                return;
            }
            if ("GET".equals(http.method) && ("/healthz".equals(http.path)
                    || "/health".equals(http.path) || "/".equals(http.path))) {
                JSONObject health = new JSONObject();
                health.put("status", isRunning() ? "ok" : "stopping");
                Backend b = backend;
                health.put("backend_ready", b != null && b.isReady());
                if (b != null) health.put("backend_status", b.readinessDetail());
                health.put("protocol", protocolMode());
                health.put("endpoint", endpoint());
                health.put("version", BuildInfo.MODULE_VERSION);
                writeJson(out, 200, health);
                recordSuccess(requestTag);
                return;
            }
            requireAuth(http);
            if ("GET".equals(http.method) && "/v1/models".equals(http.path)) {
                requireProtocol(PROTOCOL_OPENAI);
                writeJson(out, 200, modelsResponse());
                recordSuccess(requestTag);
                return;
            }
            if ("GET".equals(http.method) && http.path.startsWith("/v1/models/")
                    && http.path.length() > "/v1/models/".length()) {
                requireProtocol(PROTOCOL_OPENAI);
                String modelId = http.path.substring("/v1/models/".length());
                JSONObject model = modelById(modelId);
                if (model == null) {
                    throw new GatewayException(404, "model_not_found", "invalid_request_error",
                            "Unknown model: " + modelId);
                }
                writeJson(out, 200, model);
                recordSuccess(requestTag);
                return;
            }
            if (!"POST".equals(http.method)) {
                throw new GatewayException(404, "not_found", "Unknown local API route");
            }
            JSONObject body;
            try {
                body = new JSONObject(new String(http.body, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                throw new GatewayException(400, "invalid_json", "Request body must be one JSON object");
            }
            // Codex sends thread/session identity both as headers and in client_metadata. Claude
            // wrappers use their own session headers. Keep every raw value request-local and let
            // the normal translator hash it before it can enter persistent session state.
            String headerSession = firstNonBlank(
                    http.headers.get("thread-id"),
                    http.headers.get("x-client-request-id"),
                    http.headers.get("session-id"),
                    http.headers.get("x-claude-code-session-id"),
                    http.headers.get("x-claude-session-id"));
            if (headerSession != null && headerSession.trim().length() > 0) {
                body.put("_deekseep_client_session_id", headerSession.trim());
            }
            if ("/v1/chat/completions".equals(http.path)) {
                requireProtocol(PROTOCOL_OPENAI);
                handleChat(out, body);
                return;
            }
            if ("/v1/responses".equals(http.path)) {
                requireProtocol(PROTOCOL_OPENAI);
                handleResponses(out, body);
                return;
            }
            if ("/v1/messages".equals(http.path)) {
                requireProtocol(PROTOCOL_ANTHROPIC);
                handleAnthropicMessages(out, body);
                return;
            }
            if ("/v1/messages/count_tokens".equals(http.path)) {
                requireProtocol(PROTOCOL_ANTHROPIC);
                handleAnthropicCountTokens(out, body);
                return;
            }
            throw new GatewayException(404, "not_found", "Unknown local API route");
        } catch (EmptyConnectionIOException ignored) {
            // Port probes often connect and close without sending a request. Do not count those
            // sockets as malformed API calls or overwrite the last useful failure diagnostic.
        } catch (GatewayException e) {
            try {
                if (anthropicRequest) {
                    writeAnthropicError(socket.getOutputStream(), e.status,
                            anthropicErrorType(e), e.getMessage());
                } else {
                    writeError(socket.getOutputStream(), e.status, e.type, e.code, e.getMessage());
                }
            }
            catch (Throwable ignored) {}
            apiLog("REQUEST_FAIL route=" + requestTag + " status=" + e.status
                    + " code=" + e.code + " ms=" + (System.currentTimeMillis() - started));
            recordFailure(requestTag, e.code + ": " + e.getMessage());
        } catch (Throwable t) {
            try {
                if (anthropicRequest) {
                    writeAnthropicError(socket.getOutputStream(), 500,
                            "api_error", safeMessage(t));
                } else {
                    writeError(socket.getOutputStream(), 500, "server_error",
                            "gateway_error", safeMessage(t));
                }
            }
            catch (Throwable ignored) {}
            apiLog("REQUEST_ERROR route=" + requestTag + " ms="
                    + (System.currentTimeMillis() - started) + " error=" + safeMessage(t));
            recordFailure(requestTag, "gateway_error: " + safeMessage(t));
        } finally {
            closeQuietly(socket);
        }
    }

    private static void handleChat(final OutputStream out, JSONObject body) throws Exception {
        final boolean stream = body.optBoolean("stream", false);
        final boolean includeUsage = body.optJSONObject("stream_options") != null
                && body.optJSONObject("stream_options").optBoolean("include_usage", false);
        final String id = "chatcmpl-" + compactUuid();
        CompletionRequest request = translateChat(id, body);
        apiLog("CHAT_BEGIN id=" + id + " stream=" + stream
                + " model=" + request.requestedModel + " native_model=" + request.nativeModel
                + " thinking=" + request.reasoning + " search=" + request.search
                + " tools=" + (request.toolPlan == null ? 0 : request.toolPlan.tools.size())
                + " tool_choice=" + toolChoiceMode(request)
                + " completed_tools=" + request.completedToolCalls.size());
        ensureBackendReady();
        if (!stream) {
            CompletionResult result = completeOpenAiRequest(request, null);
            JSONObject root = new JSONObject();
            root.put("id", id);
            root.put("object", "chat.completion");
            root.put("created", nowSeconds());
            root.put("model", request.requestedModel);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", result.hasToolCalls() ? JSONObject.NULL : result.text);
            if (result.hasToolCalls()) message.put("tool_calls", chatToolCalls(result.toolCalls));
            if (result.reasoning.length() > 0) {
                message.put("reasoning_content", result.reasoning);
            }
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", result.finishReason);
            root.put("choices", new JSONArray().put(choice));
            root.put("usage", chatUsage(request.prompt, resultOutputForUsage(result),
                    result.reasoning));
            writeJson(out, 200, root);
            apiLog("CHAT_OK id=" + id + " stream=false model=" + request.requestedModel
                    + " chars=" + result.text.length() + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/chat/completions");
            return;
        }

        writeSseHeaders(out);
        final SseHeartbeat heartbeat = new SseHeartbeat(out, "openai-chat", id);
        writeChatChunk(out, id, request.requestedModel, "assistant", null, null);
        heartbeat.start();
        final boolean[] disconnected = {false};
        DeltaSink sink = new DeltaSink() {
            @Override public boolean onText(String delta) {
                if (disconnected[0] || heartbeat.isDisconnected()) return false;
                try {
                    writeChatChunk(out, id, request.requestedModel, null, delta, null);
                    return true;
                } catch (Throwable t) {
                    disconnected[0] = true;
                    return false;
                }
            }

            @Override public boolean onReasoning(String delta) {
                if (disconnected[0] || heartbeat.isDisconnected()) return false;
                try {
                    writeChatReasoningChunk(out, id, request.requestedModel, delta);
                    return true;
                } catch (Throwable t) {
                    disconnected[0] = true;
                    return false;
                }
            }

            @Override public boolean isCancelled() {
                return disconnected[0] || heartbeat.isDisconnected();
            }
        };
        try {
            CompletionResult result = completeOpenAiRequest(request,
                    request.toolsActive() ? null : sink);
            if (!disconnected[0]) {
                if (request.toolsActive()) {
                    if (result.reasoning.length() > 0) {
                        writeChatReasoningChunk(out, id, request.requestedModel, result.reasoning);
                    }
                    if (result.hasToolCalls()) {
                        writeChatToolCallChunks(out, id, request.requestedModel, result.toolCalls);
                    } else if (result.text.length() > 0) {
                        writeBufferedChatText(out, id, request.requestedModel, result.text);
                    }
                }
                writeChatChunk(out, id, request.requestedModel, null, null,
                        result.finishReason);
                if (includeUsage) {
                    writeChatUsageChunk(out, id, request.requestedModel,
                            chatUsage(request.prompt, resultOutputForUsage(result),
                                    result.reasoning));
                }
                writeSseData(out, "[DONE]");
            }
            if (disconnected[0] || heartbeat.isDisconnected()) {
                apiLog("CHAT_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/chat/completions", "client disconnected");
                return;
            }
            apiLog("CHAT_OK id=" + id + " stream=true model=" + request.requestedModel
                    + " chars=" + result.text.length() + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/chat/completions");
        } catch (Throwable t) {
            if (isClientDisconnect(t) || disconnected[0] || heartbeat.isDisconnected()) {
                apiLog("CHAT_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/chat/completions", "client disconnected");
                return;
            }
            if (!disconnected[0]) {
                GatewayException error = asGatewayException(t);
                writeSseData(out, errorObject(error.type, error.code,
                        error.getMessage()).toString());
                writeSseData(out, "[DONE]");
            }
            apiLog("CHAT_STREAM_FAIL id=" + id + " error=" + safeMessage(t));
            recordFailure("POST /v1/chat/completions", "stream: " + safeMessage(t));
            return;
        } finally {
            heartbeat.stop();
        }
    }

    private static void handleResponses(final OutputStream out, JSONObject body) throws Exception {
        final boolean stream = body.optBoolean("stream", false);
        final String id = "resp_" + compactUuid();
        final String messageId = "msg_" + compactUuid();
        CompletionRequest request = translateResponses(id, body);
        apiLog("RESPONSES_BEGIN id=" + id + " stream=" + stream
                + " model=" + request.requestedModel + " native_model=" + request.nativeModel
                + " thinking=" + request.reasoning + " search=" + request.search
                + " tools=" + (request.toolPlan == null ? 0 : request.toolPlan.tools.size())
                + " tool_choice=" + toolChoiceMode(request)
                + " completed_tools=" + request.completedToolCalls.size()
                + " previous=" + (request.previousResponseId == null
                        ? "none" : request.previousResponseId));
        ensureBackendReady();
        if (!stream) {
            CompletionResult result = completeOpenAiRequest(request, null);
            rememberResponseState(id, request, result);
            JSONObject response = responseObject(id, messageId, request, result,
                    "completed", true);
            writeJson(out, 200, response);
            apiLog("RESPONSES_OK id=" + id + " stream=false model="
                    + request.requestedModel + " chars=" + result.text.length()
                    + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/responses");
            return;
        }

        writeSseHeaders(out);
        final SseHeartbeat heartbeat = new SseHeartbeat(out, "openai-responses", id);
        final int[] sequence = {0};
        JSONObject created = responseObject(id, messageId, request,
                new CompletionResult("", "", "stop"), "in_progress", false);
        writeResponseEvent(out, "response.created", ++sequence[0],
                new JSONObject().put("response", created));
        writeResponseEvent(out, "response.in_progress", ++sequence[0],
                new JSONObject().put("response", created));
        if (request.toolsActive()) {
            heartbeat.start();
            try {
                handleBufferedResponsesStream(out, id, messageId, request, sequence, heartbeat);
            } finally {
                heartbeat.stop();
            }
            return;
        }
        JSONObject item = outputMessage(messageId, "in_progress", "");
        writeResponseEvent(out, "response.output_item.added", ++sequence[0],
                new JSONObject().put("output_index", 0).put("item", item));
        JSONObject emptyPart = new JSONObject().put("type", "output_text")
                .put("text", "").put("annotations", new JSONArray());
        writeResponseEvent(out, "response.content_part.added", ++sequence[0],
                new JSONObject().put("item_id", messageId).put("output_index", 0)
                        .put("content_index", 0).put("part", emptyPart));
        heartbeat.start();

        final boolean[] disconnected = {false};
        final StringBuilder streamedText = new StringBuilder();
        DeltaSink sink = new DeltaSink() {
            @Override public boolean onText(String delta) {
                if (disconnected[0] || heartbeat.isDisconnected()) return false;
                try {
                    streamedText.append(delta);
                    writeResponseEvent(out, "response.output_text.delta", ++sequence[0],
                            new JSONObject().put("item_id", messageId).put("output_index", 0)
                                    .put("content_index", 0).put("delta", delta));
                    return true;
                } catch (Throwable t) {
                    disconnected[0] = true;
                    return false;
                }
            }

            @Override public boolean onReasoning(String delta) {
                // Raw hidden reasoning is not mixed into Responses output text. The completed
                // object exposes only the text answer; Chat compatibility has an opt-in extension.
                return !disconnected[0] && !heartbeat.isDisconnected();
            }

            @Override public boolean isCancelled() {
                return disconnected[0] || heartbeat.isDisconnected();
            }
        };
        try {
            CompletionResult result = completeOpenAiRequest(request, sink);
            if (!disconnected[0]) {
                String text = streamedText.length() > 0 ? streamedText.toString() : result.text;
                writeResponseEvent(out, "response.output_text.done", ++sequence[0],
                        new JSONObject().put("item_id", messageId).put("output_index", 0)
                                .put("content_index", 0).put("text", text));
                JSONObject part = new JSONObject().put("type", "output_text")
                        .put("text", text).put("annotations", new JSONArray());
                writeResponseEvent(out, "response.content_part.done", ++sequence[0],
                        new JSONObject().put("item_id", messageId).put("output_index", 0)
                                .put("content_index", 0).put("part", part));
                writeResponseEvent(out, "response.output_item.done", ++sequence[0],
                        new JSONObject().put("output_index", 0)
                                .put("item", outputMessage(messageId, "completed", text)));
                JSONObject completed = responseObject(id, messageId, request,
                        new CompletionResult(text, result.reasoning, result.finishReason),
                        "completed", true);
                writeResponseEvent(out, "response.completed", ++sequence[0],
                        new JSONObject().put("response", completed));
                rememberResponseState(id, request,
                        new CompletionResult(text, result.reasoning, result.finishReason));
            }
            if (disconnected[0] || heartbeat.isDisconnected()) {
                apiLog("RESPONSES_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/responses", "client disconnected");
                return;
            }
            apiLog("RESPONSES_OK id=" + id + " stream=true model="
                    + request.requestedModel + " chars=" + result.text.length());
            recordSuccess("POST /v1/responses");
        } catch (Throwable t) {
            if (isClientDisconnect(t) || disconnected[0] || heartbeat.isDisconnected()) {
                apiLog("RESPONSES_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/responses", "client disconnected");
                return;
            }
            if (!disconnected[0]) {
                GatewayException error = asGatewayException(t);
                JSONObject failed = responseObject(id, messageId, request,
                        new CompletionResult(streamedText.toString(), "", "error"),
                        "failed", true);
                failed.put("error", errorObject(error.type, error.code,
                        error.getMessage()).getJSONObject("error"));
                writeResponseEvent(out, "response.failed", ++sequence[0],
                        new JSONObject().put("response", failed));
            }
            apiLog("RESPONSES_STREAM_FAIL id=" + id + " error=" + safeMessage(t));
            recordFailure("POST /v1/responses", "stream: " + safeMessage(t));
            return;
        } finally {
            heartbeat.stop();
        }
    }

    private static void handleAnthropicMessages(final OutputStream out, JSONObject body)
            throws Exception {
        final boolean stream = body.optBoolean("stream", false);
        final String id = "msg_" + compactUuid();
        final CompletionRequest request = translateAnthropic(id, body, true);
        apiLog("ANTHROPIC_BEGIN id=" + id + " stream=" + stream
                + " model=" + request.requestedModel + " native_model=" + request.nativeModel
                + " thinking=" + request.reasoning + " search=" + request.search
                + " max_tokens=" + request.maxOutputTokens
                + " tools=" + (request.toolPlan == null ? 0 : request.toolPlan.tools.size())
                + " tool_choice=" + toolChoiceMode(request)
                + " completed_tools=" + request.completedToolCalls.size()
                + " repeatable_tools=" + request.repeatableCompletedToolCalls.size());
        ensureBackendReady();
        if (!stream) {
            CompletionResult result = completeOpenAiRequest(request, null);
            writeJson(out, 200, anthropicMessageObject(id, request, result));
            apiLog("ANTHROPIC_OK id=" + id + " stream=false model="
                    + request.requestedModel + " chars=" + result.text.length()
                    + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/messages");
            return;
        }

        writeSseHeaders(out);
        final SseHeartbeat heartbeat = new SseHeartbeat(out, "anthropic", id);
        try {
            AnthropicStreamEmitter emitter = new AnthropicStreamEmitter(
                    out, request, heartbeat);
            heartbeat.attach(emitter);
            CompletionResult result = completeOpenAiRequest(request, emitter);
            if (emitter.isCancelled()) {
                apiLog("ANTHROPIC_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/messages", "client disconnected");
                return;
            }
            emitter.finish(result);
            apiLog("ANTHROPIC_OK id=" + id + " stream=true incremental=true model="
                    + request.requestedModel + " chars=" + result.text.length()
                    + " reasoning_chars=" + result.reasoning.length()
                    + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/messages");
        } catch (Throwable t) {
            if (isClientDisconnect(t) || heartbeat.isDisconnected()) {
                apiLog("ANTHROPIC_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/messages", "client disconnected");
                return;
            }
            GatewayException error = asGatewayException(t);
            try {
                writeAnthropicEvent(out, "error", new JSONObject().put("error",
                        new JSONObject().put("type", anthropicErrorType(error))
                                .put("message", error.getMessage())));
            } catch (Throwable ignored) {}
            apiLog("ANTHROPIC_STREAM_FAIL id=" + id + " error=" + safeMessage(t));
            recordFailure("POST /v1/messages", error.code + ": " + error.getMessage());
        } finally {
            heartbeat.stop();
        }
    }

    private static void handleAnthropicCountTokens(OutputStream out, JSONObject body)
            throws Exception {
        CompletionRequest request = translateAnthropic("count_" + compactUuid(), body, false);
        writeJson(out, 200, new JSONObject().put("input_tokens",
                approximateTokens(request.prompt)));
        recordSuccess("POST /v1/messages/count_tokens");
    }

    /**
     * Converts native DeepSeek deltas to the real Anthropic block lifecycle.
     *
     * <p>Claude Code always supplies its client tools. Buffering every such request made the UI
     * sit at zero tokens until generation ended. This emitter forwards reasoning immediately,
     * streams ordinary text as soon as it is distinguishable from a tool envelope, and keeps only
     * possible tool markup private until {@link OpenAiToolBridge} has validated it.</p>
     */
    private static final class AnthropicStreamEmitter implements DeltaSink {
        private static final int TOOL_MARKER_CONTEXT = 128;
        /**
         * Hold a short first-text prefix on tool-bearing turns. DeepSeek often writes “I will…”
         * immediately before either a real envelope or an accidental early stop. Keeping this
         * prefix private lets the gateway repair the turn without flipping Claude Code from
         * thinking to responding and back again; long ordinary answers still stream promptly.
         */
        private static final int INITIAL_AGENT_TEXT_GUARD = 512;
        private final OutputStream out;
        private final CompletionRequest request;
        private final SseHeartbeat heartbeat;
        private final boolean guardToolMarkup;
        private final boolean guardWorkspaceMutation;
        private final StringBuilder rawText = new StringBuilder();
        private final StringBuilder observedReasoning = new StringBuilder();
        private final StringBuilder reasoningBlock = new StringBuilder();
        private final StringBuilder pendingText = new StringBuilder();
        private final StringBuilder pendingReasoning = new StringBuilder();
        private int nextIndex;
        private int thinkingIndex = -1;
        private int textIndex = -1;
        private boolean textToolSuppressed;
        private boolean reasoningToolSuppressed;
        private boolean generationTextEmitted;
        private boolean publicTextStarted;
        private boolean started;
        private boolean terminal;
        private boolean upstreamSatisfied;
        private volatile boolean disconnected;

        AnthropicStreamEmitter(OutputStream out, CompletionRequest request,
                               SseHeartbeat heartbeat) {
            this.out = out;
            this.request = request;
            this.heartbeat = heartbeat;
            this.guardToolMarkup = request != null && request.toolsActive();
            this.guardWorkspaceMutation = requiresWorkspaceFileAction(request);
        }

        synchronized void beginThinking() throws IOException, JSONException {
            startIfNeeded();
        }

        @Override public synchronized void onUpstreamStarted() throws Exception {
            startIfNeeded();
        }

        @Override public synchronized boolean onText(String delta) {
            if (delta == null || delta.length() == 0) return !isCancelled();
            if (isCancelled()) return false;
            try {
                startIfNeeded();
                flushReasoningPending(true);
                rawText.append(delta);
                heartbeat.addAnthropicOutput(delta);
                if (!guardToolMarkup) {
                    emitTextValue(delta);
                } else if (!textToolSuppressed) {
                    pendingText.append(delta);
                    drainTextPending(false);
                }
                if (guardToolMarkup && !upstreamSatisfied
                        && OpenAiToolBridge.resemblesCallSyntax(
                                rawText.toString(), request.toolPlan)
                        && !OpenAiToolBridge.parseCalls(rawText.toString(), request.toolPlan)
                                .isEmpty()) {
                    // The caller can execute the first complete action now. Do not wait while
                    // DeepSeek goes on to fabricate subsequent [tool] results in plain text.
                    upstreamSatisfied = true;
                }
                return true;
            } catch (Throwable t) {
                disconnected = true;
                return false;
            }
        }

        @Override public synchronized boolean onReasoning(String delta) {
            if (delta == null || delta.length() == 0) return !isCancelled();
            if (isCancelled()) return false;
            try {
                startIfNeeded();
                // DeepSeek normally emits all reasoning before answer text. If a later reasoning
                // segment appears, close the text block and create another valid thinking block.
                flushTextPending(true);
                closeTextBlock();
                observedReasoning.append(delta);
                heartbeat.addAnthropicOutput(delta);
                if (!guardToolMarkup) {
                    emitReasoningValue(delta);
                } else if (!reasoningToolSuppressed) {
                    pendingReasoning.append(delta);
                    drainReasoningPending(false);
                }
                return true;
            } catch (Throwable t) {
                disconnected = true;
                return false;
            }
        }

        @Override public boolean isCancelled() {
            return disconnected || heartbeat.isDisconnected();
        }

        @Override public boolean isSatisfied() { return upstreamSatisfied; }

        synchronized boolean hasSuppressedToolMarkup() {
            return textToolSuppressed || reasoningToolSuppressed;
        }

        synchronized void finish(CompletionResult result) throws Exception {
            if (result == null) result = new CompletionResult("", "", "stop");
            startIfNeeded();
            emitMissingReasoning(result.reasoning);
            flushReasoningPending(true);
            closeThinkingBlock();

            if (result.hasToolCalls()) {
                // pendingText contains only the not-yet-committed tail. If this generation was a
                // tool call, discard that tail and expose only the validated structured call.
                pendingText.setLength(0);
                closeTextBlock();
                for (OpenAiToolBridge.Call call : result.toolCalls) emitToolCall(call);
            } else {
                String finalText = result.text == null ? "" : result.text;
                String observed = rawText.toString();
                if (observed.length() == 0 && finalText.length() > 0) {
                    onText(finalText);
                } else if (finalText.startsWith(observed)
                        && finalText.length() > observed.length()) {
                    onText(finalText.substring(observed.length()));
                } else if (!generationTextEmitted && !textToolSuppressed
                        && finalText.length() > 0 && !finalText.equals(observed)) {
                    pendingText.setLength(0);
                    rawText.setLength(0);
                    onText(finalText);
                }
                if (textToolSuppressed || (guardToolMarkup
                        && OpenAiToolBridge.resemblesCallSyntax(
                                finalText, request.toolPlan))) {
                    throw new GatewayException(502, "unparsed_tool_markup", "server_error",
                            "DeepSeek returned tool markup that could not be validated");
                }
                flushTextPending(true);
                if (!generationTextEmitted) ensureTextBlock();
                closeTextBlock();
            }
            terminal = true;
            writeAnthropicMessageEnd(out, request, result,
                    result.text == null ? rawText.toString() : result.text,
                    heartbeat.anthropicOutputTokens());
        }

        /** Close one native attempt before a format/duplicate recovery attempt starts. */
        synchronized void nextGeneration() throws Exception {
            if (guardToolMarkup && (reasoningToolSuppressed
                    || OpenAiToolBridge.resemblesCallSyntax(
                            pendingReasoning.toString(), request.toolPlan))) {
                pendingReasoning.setLength(0);
            } else {
                flushReasoningPending(true);
            }
            closeThinkingBlock();
            if (guardToolMarkup && (textToolSuppressed
                    || OpenAiToolBridge.resemblesCallSyntax(
                            rawText.toString(), request.toolPlan)
                    || possibleToolEnvelopeStart(
                            pendingText.toString(), request.toolPlan) >= 0)) {
                pendingText.setLength(0);
            } else if (!generationTextEmitted) {
                // A short narrated promise is not part of the repaired assistant turn.
                pendingText.setLength(0);
            } else {
                flushTextPending(true);
            }
            closeTextBlock();
            rawText.setLength(0);
            pendingText.setLength(0);
            pendingReasoning.setLength(0);
            textToolSuppressed = false;
            reasoningToolSuppressed = false;
            generationTextEmitted = false;
            upstreamSatisfied = false;
        }

        /** Keep Claude Code in its thinking state after a cumulative usage heartbeat. */
        synchronized void onAnthropicActivity(int outputTokens)
                throws IOException, JSONException {
            if (terminal || disconnected) return;
            startIfNeeded();
            writeAnthropicEvent(out, "ping", new JSONObject());
            boolean resumeThinking = !publicTextStarted && textIndex < 0;
            writeAnthropicActivityEvent(out, outputTokens);
            if (resumeThinking) {
                closeThinkingBlock();
                ensureThinkingBlock();
            }
        }

        /** Start the Anthropic lifecycle only after the native upstream has actually started. */
        private void startIfNeeded() throws IOException, JSONException {
            if (started) return;
            JSONObject startMessage = new JSONObject().put("id", request.requestId)
                    .put("type", "message").put("role", "assistant")
                    .put("model", request.requestedModel).put("content", new JSONArray())
                    .put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                    .put("usage", anthropicUsage(request.prompt, "", ""));
            writeAnthropicEvent(out, "message_start",
                    new JSONObject().put("message", startMessage));
            started = true;
            ensureThinkingBlock();
            heartbeat.start();
        }

        private void emitMissingReasoning(String complete) throws Exception {
            if (complete == null || complete.length() == 0) return;
            String seen = observedReasoning.toString();
            String missing = complete.startsWith(seen) ? complete.substring(seen.length())
                    : (seen.length() == 0 ? complete : "");
            if (missing.length() == 0) return;
            if (!onReasoning(missing)) {
                throw new ClientDisconnectedIOException(
                        new IOException("client disconnected during thinking stream"));
            }
        }

        private void ensureThinkingBlock() throws IOException, JSONException {
            if (thinkingIndex >= 0) return;
            thinkingIndex = nextIndex++;
            reasoningBlock.setLength(0);
            writeAnthropicEvent(out, "content_block_start",
                    new JSONObject().put("index", thinkingIndex).put("content_block",
                            new JSONObject().put("type", "thinking")
                                    .put("thinking", "").put("signature", "")));
        }

        private void closeThinkingBlock() throws IOException, JSONException {
            if (thinkingIndex < 0) return;
            writeAnthropicEvent(out, "content_block_delta",
                    new JSONObject().put("index", thinkingIndex).put("delta",
                            new JSONObject().put("type", "signature_delta")
                                    .put("signature", reasoningSignature(
                                            reasoningBlock.toString()))));
            writeAnthropicEvent(out, "content_block_stop",
                    new JSONObject().put("index", thinkingIndex));
            thinkingIndex = -1;
            reasoningBlock.setLength(0);
        }

        private void ensureTextBlock() throws IOException, JSONException {
            if (textIndex >= 0) return;
            publicTextStarted = true;
            textIndex = nextIndex++;
            writeAnthropicEvent(out, "content_block_start",
                    new JSONObject().put("index", textIndex).put("content_block",
                            new JSONObject().put("type", "text").put("text", "")));
        }

        private void emitText(String value) throws IOException, JSONException {
            if (value == null || value.length() == 0) return;
            writeAnthropicEvent(out, "content_block_delta",
                    new JSONObject().put("index", textIndex).put("delta",
                            new JSONObject().put("type", "text_delta").put("text", value)));
        }

        private void emitTextValue(String value) throws IOException, JSONException {
            if (value == null || value.length() == 0) return;
            generationTextEmitted = true;
            closeThinkingBlock();
            ensureTextBlock();
            // Preserve the native token cadence. Re-splitting a completed tail into artificial
            // 48-character bursts makes Claude Code look buffered even though the backend is live.
            emitText(value);
        }

        private void emitReasoningValue(String value) throws IOException, JSONException {
            if (value == null || value.length() == 0) return;
            ensureThinkingBlock();
            reasoningBlock.append(value);
            writeAnthropicEvent(out, "content_block_delta",
                    new JSONObject().put("index", thinkingIndex).put("delta",
                            new JSONObject().put("type", "thinking_delta")
                                    .put("thinking", value)));
        }

        private void closeTextBlock() throws IOException, JSONException {
            if (textIndex < 0) return;
            writeAnthropicEvent(out, "content_block_stop",
                    new JSONObject().put("index", textIndex));
            textIndex = -1;
        }

        private void emitToolCall(OpenAiToolBridge.Call call)
                throws IOException, JSONException {
            int index = nextIndex++;
            writeAnthropicEvent(out, "content_block_start",
                    new JSONObject().put("index", index).put("content_block",
                            new JSONObject().put("type", "tool_use")
                                    .put("id", anthropicToolId(call)).put("name", call.name)
                                    .put("input", new JSONObject())));
            for (String delta : stringChunks(call.arguments, 64)) {
                writeAnthropicEvent(out, "content_block_delta",
                        new JSONObject().put("index", index).put("delta",
                                new JSONObject().put("type", "input_json_delta")
                                        .put("partial_json", delta)));
            }
            writeAnthropicEvent(out, "content_block_stop",
                    new JSONObject().put("index", index));
        }

        private void drainTextPending(boolean terminal) throws IOException, JSONException {
            if (pendingText.length() == 0 || textToolSuppressed) return;
            int envelope = toolEnvelopeStart(pendingText.toString(), request.toolPlan);
            if (envelope >= 0) {
                // Narration preceding a validated tool envelope is protocol noise. Exposing it
                // makes Claude Code briefly render a final answer before the actual tool_use.
                pendingText.setLength(0);
                textToolSuppressed = true;
                return;
            }
            int possible = terminal ? -1 : possibleToolEnvelopeStart(
                    pendingText.toString(), request.toolPlan);
            int count = possible < 0 ? pendingText.length() : possible;
            // A workspace-write request is not complete until a validated tool call exists.
            // Keep arbitrary-length source output private so it cannot briefly appear as a final
            // answer before the bounded repair converts the turn to Write/Edit/apply_patch.
            if (!terminal && guardWorkspaceMutation) count = 0;
            if (!terminal && !generationTextEmitted && possible < 0
                    && shouldGuardInitialAgentText(pendingText.toString())) {
                count = Math.max(0, count - INITIAL_AGENT_TEXT_GUARD);
            }
            count = safeSplitIndex(pendingText, count);
            if (count <= 0) return;
            emitTextValue(pendingText.substring(0, count));
            pendingText.delete(0, count);
        }

        private void flushTextPending(boolean terminal) throws IOException, JSONException {
            if (!guardToolMarkup) return;
            drainTextPending(terminal);
            if (textToolSuppressed) pendingText.setLength(0);
        }

        private void drainReasoningPending(boolean terminal) throws IOException, JSONException {
            if (pendingReasoning.length() == 0 || reasoningToolSuppressed) return;
            int envelope = toolEnvelopeStart(pendingReasoning.toString(), request.toolPlan);
            if (envelope >= 0) {
                emitReasoningValue(pendingReasoning.substring(0, envelope));
                pendingReasoning.setLength(0);
                reasoningToolSuppressed = true;
                return;
            }
            int possible = terminal ? -1
                    : possibleToolEnvelopeStart(pendingReasoning.toString(), request.toolPlan);
            int count = possible < 0 ? pendingReasoning.length() : possible;
            count = safeSplitIndex(pendingReasoning, count);
            if (count <= 0) return;
            emitReasoningValue(pendingReasoning.substring(0, count));
            pendingReasoning.delete(0, count);
        }

        private void flushReasoningPending(boolean terminal)
                throws IOException, JSONException {
            if (!guardToolMarkup) return;
            drainReasoningPending(terminal);
            if (reasoningToolSuppressed) pendingReasoning.setLength(0);
        }

        private static int safeSplitIndex(CharSequence value, int index) {
            if (index > 0 && index < value.length()
                    && Character.isHighSurrogate(value.charAt(index - 1))
                    && Character.isLowSurrogate(value.charAt(index))) return index - 1;
            return index;
        }

        private static boolean shouldGuardInitialAgentText(String value) {
            if (value == null || value.length() == 0) return true;
            String normalized = value.trim().toLowerCase(Locale.US).replace('’', '\'');
            if (normalized.length() == 0) return true;
            String[] prefixes = new String[] {
                    "i will ", "i'll ", "let me ", "next, i will ", "first, i will ",
                    "i'll start", "i'll first", "i need to ", "i'm going to ",
                    "我将", "我会", "我先", "我来", "我现在", "让我先", "接下来我",
                    "下面我将", "首先，我将", "首先我将"
            };
            for (String prefix : prefixes) {
                if (normalized.startsWith(prefix) || prefix.startsWith(normalized)) return true;
            }
            return false;
        }

        /** Returns the beginning of a definite private tool envelope, or -1. */
        private static int toolEnvelopeStart(String value, OpenAiToolBridge.Plan plan) {
            if (value == null || value.length() == 0) return -1;
            int shorthand = OpenAiToolBridge.shorthandCallStart(value, plan);
            String normalized = value.replace('▁', '_').replace('｜', '|')
                    .toLowerCase(Locale.US);
            String[] markers = new String[] {
                    "deekseep_tool_calls", "\"tool_calls\"", "tool_call_begin",
                    "<tool>", "<tool:", "<tool ", "<tool_call", "<|tool_calls",
                    "<function_calls", "<invoke", "tool call:",
                    "<function=", "assistant to=", "assistant recipient="
            };
            int marker = -1;
            for (String candidate : markers) {
                int found = normalized.indexOf(candidate);
                if (found >= 0 && (marker < 0 || found < marker)) marker = found;
            }
            // DeepSeek occasionally truncates or prefixes the requested envelope key (for
            // example "ekseep_tool_calls"). The parser intentionally accepts keys ending in
            // tool_calls, so the streaming redactor must recognize the same family before a
            // long arguments object pushes the malformed prefix outside the rolling tail.
            int suffixKey = normalized.indexOf("tool_calls");
            if (suffixKey >= 0) {
                int objectStart = normalized.lastIndexOf('{', suffixKey);
                if (objectStart >= Math.max(0, suffixKey - TOOL_MARKER_CONTEXT)
                        && (marker < 0 || suffixKey < marker)) {
                    marker = suffixKey;
                }
            }
            int narrated = normalized.indexOf("requested tool ");
            if (narrated < 0) narrated = normalized.indexOf("request tool ");
            if (narrated >= 0 && (normalized.indexOf("with arguments", narrated) >= 0
                    || normalized.indexOf("with input", narrated) >= 0)
                    && (marker < 0 || narrated < marker)) {
                marker = narrated;
            }
            if (marker < 0) {
                int name = normalized.indexOf("\"name\"");
                int arguments = normalized.indexOf("\"arguments\"");
                if (name >= 0 && arguments >= 0) {
                    int brace = normalized.lastIndexOf('{', Math.min(name, arguments));
                    if (brace >= 0 && Math.abs(name - arguments) < TOOL_MARKER_CONTEXT) {
                        marker = Math.min(name, arguments);
                    }
                }
            }
            if (marker < 0) return shorthand;

            int floor = Math.max(0, marker - TOOL_MARKER_CONTEXT);
            int start = marker;
            int brace = normalized.lastIndexOf('{', marker);
            int bracket = normalized.lastIndexOf('[', marker);
            int angle = normalized.lastIndexOf('<', marker);
            int fence = normalized.lastIndexOf("```", marker);
            if (brace >= floor) start = Math.min(start, brace);
            if (bracket >= floor) start = Math.min(start, bracket);
            if (angle >= floor) start = Math.min(start, angle);
            if (fence >= floor) start = Math.min(start, fence);
            return shorthand < 0 ? start : Math.min(start, shorthand);
        }

        /**
         * Returns the earliest suffix that could still grow into a private tool envelope.
         * Unlike a fixed 256-character tail this normally returns -1, so ordinary tokens are
         * forwarded immediately. Only a short partial marker/first JSON key remains buffered.
         */
        private static int possibleToolEnvelopeStart(
                String value, OpenAiToolBridge.Plan plan) {
            if (value == null || value.length() == 0) return -1;
            int shorthand = OpenAiToolBridge.possibleShorthandCallStart(value, plan);
            String normalized = value.replace('▁', '_').replace('｜', '|')
                    .toLowerCase(Locale.US);
            String[] prefixes = new String[] {
                    "{\"deekseep_tool_calls\"", "{\"tool_calls\"",
                    "<tool>", "<tool:", "<tool ", "<tool_call", "<|tool_calls",
                    "<function_calls", "<invoke", "tool call:",
                    "<function=", "assistant to=", "assistant recipient=",
                    "requested tool ", "request tool ", "[assistant]"
            };
            int earliest = -1;
            int floor = Math.max(0, normalized.length() - TOOL_MARKER_CONTEXT);
            for (int start = floor; start < normalized.length(); start++) {
                String suffix = normalized.substring(start);
                for (String prefix : prefixes) {
                    if (suffix.length() < prefix.length() && prefix.startsWith(suffix)) {
                        if (isMarkerBoundary(normalized, start)
                                && (earliest < 0 || start < earliest)) earliest = start;
                    }
                }
            }

            int narrated = normalized.lastIndexOf("requested tool ");
            if (narrated < 0) narrated = normalized.lastIndexOf("request tool ");
            if (narrated >= 0 && normalized.length() - narrated <= 1024
                    && normalized.indexOf("with arguments", narrated) < 0
                    && normalized.indexOf("with input", narrated) < 0) {
                int assistant = normalized.lastIndexOf("[assistant]", narrated);
                int start = assistant >= 0 && narrated - assistant <= 48
                        ? assistant : narrated;
                if (earliest < 0 || start < earliest) earliest = start;
            }
            int assistant = normalized.lastIndexOf("[assistant]");
            if (assistant >= 0 && normalized.length() - assistant <= 1024) {
                String after = normalized.substring(assistant + "[assistant]".length()).trim();
                if (after.length() == 0 || "requested tool ".startsWith(after)
                        || "request tool ".startsWith(after)
                        || (after.startsWith("requested tool ")
                        && !after.contains("with arguments") && !after.contains("with input"))
                        || (after.startsWith("request tool ")
                        && !after.contains("with arguments") && !after.contains("with input"))) {
                    if (earliest < 0 || assistant < earliest) earliest = assistant;
                }
            }

            // The requested envelope is JSON. Hold a partial first object key so a truncated key
            // such as "ekseep_tool_calls" cannot leak before its decisive suffix arrives.
            for (int brace = normalized.length() - 1; brace >= floor; brace--) {
                if (normalized.charAt(brace) != '{') continue;
                String tail = normalized.substring(brace + 1);
                int cursor = 0;
                while (cursor < tail.length() && Character.isWhitespace(tail.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < tail.length() && tail.charAt(cursor) == '"') cursor++;
                int keyStart = cursor;
                while (cursor < tail.length()) {
                    char ch = tail.charAt(cursor);
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') cursor++;
                    else break;
                }
                boolean stillFirstKey = cursor == tail.length()
                        || (cursor < tail.length() && tail.charAt(cursor) == '"'
                        && tail.substring(cursor + 1).trim().length() == 0);
                if (stillFirstKey && cursor - keyStart <= 48) {
                    int start = brace;
                    int fence = normalized.lastIndexOf("```", brace);
                    int bracket = normalized.lastIndexOf('[', brace);
                    if (fence >= floor && brace - fence <= 16) start = fence;
                    else if (bracket >= floor && brace - bracket <= 8) start = bracket;
                    if (earliest < 0 || start < earliest) earliest = start;
                }
                break;
            }
            return shorthand < 0 ? earliest
                    : (earliest < 0 ? shorthand : Math.min(earliest, shorthand));
        }

        private static boolean isMarkerBoundary(String value, int start) {
            if (start <= 0) return true;
            char before = value.charAt(start - 1);
            char first = value.charAt(start);
            if (first == '{' || first == '[' || first == '<' || first == '`'
                    || first == '"') return true;
            return !Character.isLetterOrDigit(before) && before != '_';
        }
    }

    private static void writeAnthropicMessageEnd(OutputStream out, CompletionRequest request,
                                                  CompletionResult result, String text,
                                                  int outputTokenFloor)
            throws IOException, JSONException {
        JSONObject usage = anthropicUsage(request.prompt,
                result.hasToolCalls() ? resultOutputForUsage(result) : text, result.reasoning);
        writeAnthropicEvent(out, "message_delta", new JSONObject()
                .put("delta", new JSONObject().put("stop_reason", anthropicStopReason(result))
                        .put("stop_sequence", JSONObject.NULL))
                .put("usage", new JSONObject().put("output_tokens",
                        Math.max(outputTokenFloor, usage.optInt("output_tokens", 0)))));
        writeAnthropicEvent(out, "message_stop", new JSONObject());
    }

    /** A non-terminal cumulative usage event that Claude Code does not filter like SSE ping. */
    private static void writeAnthropicActivityEvent(OutputStream out, int outputTokens)
            throws IOException, JSONException {
        writeAnthropicEvent(out, "message_delta", new JSONObject()
                .put("delta", new JSONObject()
                        .put("stop_reason", JSONObject.NULL)
                        .put("stop_sequence", JSONObject.NULL))
                .put("usage", new JSONObject().put("output_tokens",
                        Math.max(0, outputTokens))));
    }

    private static void handleBufferedResponsesStream(OutputStream out, String id,
                                                      String messageId,
                                                      CompletionRequest request,
                                                      int[] sequence,
                                                      SseHeartbeat heartbeat) {
        try {
            CompletionResult result = completeOpenAiRequest(request, null);
            if (result.hasToolCalls()) {
                for (int index = 0; index < result.toolCalls.size(); index++) {
                    OpenAiToolBridge.Call call = result.toolCalls.get(index);
                    JSONObject added = responseToolItem(call, "in_progress", false);
                    writeResponseEvent(out, "response.output_item.added", ++sequence[0],
                            new JSONObject().put("output_index", index).put("item", added));
                    if (OpenAiToolBridge.FUNCTION.equals(call.kind)) {
                        for (String delta : stringChunks(call.arguments, 192)) {
                            writeResponseEvent(out, "response.function_call_arguments.delta",
                                    ++sequence[0], new JSONObject().put("item_id", call.itemId)
                                            .put("call_id", call.callId)
                                            .put("output_index", index).put("delta", delta));
                        }
                        writeResponseEvent(out, "response.function_call_arguments.done",
                                ++sequence[0], new JSONObject().put("item_id", call.itemId)
                                        .put("call_id", call.callId)
                                        .put("output_index", index).put("name", call.name)
                                        .put("arguments", call.arguments));
                    } else if (OpenAiToolBridge.CUSTOM.equals(call.kind)) {
                        String input = call.customInput();
                        for (String delta : stringChunks(input, 192)) {
                            writeResponseEvent(out, "response.custom_tool_call_input.delta",
                                    ++sequence[0], new JSONObject().put("item_id", call.itemId)
                                            .put("call_id", call.callId)
                                            .put("output_index", index).put("delta", delta));
                        }
                        writeResponseEvent(out, "response.custom_tool_call_input.done",
                                ++sequence[0], new JSONObject().put("item_id", call.itemId)
                                        .put("call_id", call.callId)
                                        .put("output_index", index).put("input", input));
                    }
                    writeResponseEvent(out, "response.output_item.done", ++sequence[0],
                            new JSONObject().put("output_index", index)
                                    .put("item", responseToolItem(call, "completed", true)));
                }
            } else {
                JSONObject item = outputMessage(messageId, "in_progress", "");
                writeResponseEvent(out, "response.output_item.added", ++sequence[0],
                        new JSONObject().put("output_index", 0).put("item", item));
                JSONObject emptyPart = new JSONObject().put("type", "output_text")
                        .put("text", "").put("annotations", new JSONArray());
                writeResponseEvent(out, "response.content_part.added", ++sequence[0],
                        new JSONObject().put("item_id", messageId).put("output_index", 0)
                                .put("content_index", 0).put("part", emptyPart));
                for (String delta : stringChunks(result.text, 192)) {
                    writeResponseEvent(out, "response.output_text.delta", ++sequence[0],
                            new JSONObject().put("item_id", messageId).put("output_index", 0)
                                    .put("content_index", 0).put("delta", delta));
                }
                writeResponseEvent(out, "response.output_text.done", ++sequence[0],
                        new JSONObject().put("item_id", messageId).put("output_index", 0)
                                .put("content_index", 0).put("text", result.text));
                JSONObject part = new JSONObject().put("type", "output_text")
                        .put("text", result.text).put("annotations", new JSONArray());
                writeResponseEvent(out, "response.content_part.done", ++sequence[0],
                        new JSONObject().put("item_id", messageId).put("output_index", 0)
                                .put("content_index", 0).put("part", part));
                writeResponseEvent(out, "response.output_item.done", ++sequence[0],
                        new JSONObject().put("output_index", 0)
                                .put("item", outputMessage(messageId, "completed", result.text)));
            }
            rememberResponseState(id, request, result);
            JSONObject completed = responseObject(id, messageId, request, result,
                    "completed", true);
            writeResponseEvent(out, "response.completed", ++sequence[0],
                    new JSONObject().put("response", completed));
            apiLog("RESPONSES_OK id=" + id + " stream=true model="
                    + request.requestedModel + " chars=" + result.text.length()
                    + " tool_calls=" + result.toolCalls.size());
            emittedToolCalls.addAndGet(result.toolCalls.size());
            logToolCalls(id, result.toolCalls);
            recordSuccess("POST /v1/responses");
        } catch (Throwable t) {
            if (isClientDisconnect(t) || (heartbeat != null && heartbeat.isDisconnected())) {
                apiLog("RESPONSES_CLIENT_DISCONNECTED id=" + id);
                recordCancelled("POST /v1/responses", "client disconnected");
                return;
            }
            GatewayException error = asGatewayException(t);
            try {
                JSONObject failed = responseObject(id, messageId, request,
                        new CompletionResult("", "", "error"), "failed", false);
                failed.put("error", errorObject(error.type, error.code,
                        error.getMessage()).getJSONObject("error"));
                writeResponseEvent(out, "response.failed", ++sequence[0],
                        new JSONObject().put("response", failed));
            } catch (Throwable ignored) {}
            apiLog("RESPONSES_STREAM_FAIL id=" + id + " error=" + safeMessage(t));
            recordFailure("POST /v1/responses", error.code + ": " + error.getMessage());
        }
    }

    private static CompletionRequest translateAnthropic(String id, JSONObject body,
                                                         boolean requireMaxTokens)
            throws GatewayException, JSONException {
        String requestedModel = requiredString(body, "model");
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            throw new GatewayException(400, "missing_messages",
                    "messages must be a non-empty array");
        }
        if (requireMaxTokens && !body.has("max_tokens")) {
            throw new GatewayException(400, "missing_max_tokens", "max_tokens is required");
        }
        int max = positiveLimit(body, "max_tokens", 0);
        JSONArray chatMessages = anthropicMessagesAsChat(messages);
        String system = anthropicSystemText(body.opt("system"));
        String basePrompt = messagesToPrompt(chatMessages, system);

        JSONArray chatTools = anthropicToolsAsChat(body.optJSONArray("tools"));
        Object toolChoice = anthropicToolChoice(body.opt("tool_choice"));
        boolean parallel = true;
        JSONObject choiceObject = body.optJSONObject("tool_choice");
        if (choiceObject != null) {
            parallel = !choiceObject.optBoolean("disable_parallel_tool_use", false);
        }
        if (body.has("disable_parallel_tool_use")) {
            parallel = !body.optBoolean("disable_parallel_tool_use", false);
        }
        OpenAiToolBridge.Plan toolPlan = OpenAiToolBridge.chatPlan(
                chatTools, toolChoice, parallel);
        ToolHistory history = chatToolHistory(chatMessages);
        String prompt = OpenAiToolBridge.addInstructions(basePrompt, toolPlan, id);
        ModelSpec spec = modelSpec(requestedModel, body);
        // Claude Code's planning UI expects a thinking block. Its request may omit Anthropic's
        // thinking field when a custom model alias is used, so tool-bearing Agent turns enable
        // DeepSeek's native THINK fragment explicitly; public text remains the actual answer.
        boolean agentReasoning = spec.reasoning || toolPlan.active();
        return new CompletionRequest(id, requestedModel, spec.nativeModel, basePrompt, prompt,
                agentReasoning, spec.search || anthropicWebSearchRequested(
                        body.optJSONArray("tools")), max, toolPlan, null, false)
                .withToolHistory(history)
                .withClientSessionScope(clientSessionScope(body));
    }

    private static JSONArray anthropicMessagesAsChat(JSONArray messages)
            throws GatewayException, JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                throw new GatewayException(400, "invalid_message",
                        "Every message must be an object");
            }
            String role = message.optString("role", "").toLowerCase(Locale.US);
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new GatewayException(400, "invalid_role",
                        "Anthropic messages support only user and assistant roles");
            }
            Object content = message.opt("content");
            if (content instanceof String || content == null || content == JSONObject.NULL) {
                out.put(new JSONObject().put("role", role)
                        .put("content", content == null ? "" : content));
                continue;
            }
            if (!(content instanceof JSONArray)) {
                throw new GatewayException(400, "invalid_content",
                        "Anthropic message content must be a string or array");
            }
            JSONArray blocks = (JSONArray) content;
            StringBuilder text = new StringBuilder();
            JSONArray calls = new JSONArray();
            ArrayList<JSONObject> toolResults = new ArrayList<JSONObject>();
            for (int b = 0; b < blocks.length(); b++) {
                JSONObject block = blocks.optJSONObject(b);
                if (block == null) continue;
                String type = block.optString("type", "text").toLowerCase(Locale.US);
                if ("text".equals(type)) {
                    if (text.length() > 0) text.append('\n');
                    text.append(block.optString("text", ""));
                } else if ("tool_use".equals(type) && "assistant".equals(role)) {
                    String callId = block.optString("id", "toolu_" + compactUuid());
                    String name = block.optString("name", "").trim();
                    if (name.length() == 0) {
                        throw new GatewayException(400, "invalid_tool_use",
                                "tool_use.name is required");
                    }
                    Object input = block.opt("input");
                    if (input == null || input == JSONObject.NULL) input = new JSONObject();
                    calls.put(new JSONObject().put("id", callId).put("type", "function")
                            .put("function", new JSONObject().put("name", name)
                                    .put("arguments", stringifyToolValue(input))));
                } else if ("tool_result".equals(type) && "user".equals(role)) {
                    String callId = block.optString("tool_use_id", "").trim();
                    if (callId.length() == 0) {
                        throw new GatewayException(400, "invalid_tool_result",
                                "tool_result.tool_use_id is required");
                    }
                    String value = anthropicBlockText(block.opt("content"));
                    if (block.optBoolean("is_error", false)) value = "Error: " + value;
                    toolResults.add(new JSONObject().put("role", "tool")
                            .put("tool_call_id", callId).put("content", value));
                } else if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                    // Thinking blocks are signed transport context. The local gateway does not
                    // need to replay them into the public prompt, and never validates a remote
                    // Anthropic signature.
                } else if ("image".equals(type) || "document".equals(type)
                        || "search_result".equals(type)) {
                    throw new GatewayException(400, "unsupported_multimodal_input",
                            "This local gateway currently accepts text input only");
                } else {
                    // Preserve forward-compatible public content as text instead of discarding it.
                    if (text.length() > 0) text.append('\n');
                    text.append("Anthropic content block (type=").append(type)
                            .append("): ").append(block.toString());
                }
            }
            if (text.length() > 0 || (calls.length() == 0 && toolResults.isEmpty())) {
                JSONObject converted = new JSONObject().put("role", role)
                        .put("content", text.toString());
                if (calls.length() > 0) converted.put("tool_calls", calls);
                out.put(converted);
            } else if (calls.length() > 0) {
                out.put(new JSONObject().put("role", role).put("content", "")
                        .put("tool_calls", calls));
            }
            for (JSONObject toolResult : toolResults) out.put(toolResult);
        }
        return out;
    }

    private static String anthropicSystemText(Object system) throws GatewayException {
        if (system == null || system == JSONObject.NULL) return null;
        if (system instanceof String) return ((String) system).trim();
        return anthropicBlockText(system).trim();
    }

    private static String anthropicBlockText(Object content) throws GatewayException {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return stringifyToolValue(content);
        JSONArray blocks = (JSONArray) content;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) {
            Object raw = blocks.opt(i);
            if (raw instanceof String) {
                if (out.length() > 0) out.append('\n');
                out.append(raw);
                continue;
            }
            JSONObject block = raw instanceof JSONObject ? (JSONObject) raw : null;
            if (block == null) continue;
            String type = block.optString("type", "text").toLowerCase(Locale.US);
            if ("text".equals(type)) {
                if (out.length() > 0) out.append('\n');
                out.append(block.optString("text", ""));
            } else if ("image".equals(type) || "document".equals(type)) {
                throw new GatewayException(400, "unsupported_multimodal_input",
                        "This local gateway currently accepts text input only");
            } else {
                if (out.length() > 0) out.append('\n');
                out.append(block.toString());
            }
        }
        return out.toString();
    }

    private static JSONArray anthropicToolsAsChat(JSONArray tools)
            throws GatewayException, JSONException {
        if (tools == null || tools.length() == 0) return null;
        JSONArray converted = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) {
                throw new GatewayException(400, "invalid_tool",
                        "Every tools entry must be an object");
            }
            String type = tool.optString("type", "").toLowerCase(Locale.US);
            if (type.startsWith("web_search_") || type.startsWith("web_fetch_")
                    || type.startsWith("code_execution_") || type.startsWith("computer_")) {
                continue;
            }
            String name = tool.optString("name", "").trim();
            if (name.length() == 0) {
                // Unknown server-side tool variants are ignored; client tools always have a name.
                if (type.length() > 0) continue;
                throw new GatewayException(400, "invalid_tool", "tool.name is required");
            }
            JSONObject schema = tool.optJSONObject("input_schema");
            if (schema == null) schema = new JSONObject();
            JSONObject function = new JSONObject().put("name", name)
                    .put("description", tool.optString("description", ""))
                    .put("parameters", schema);
            converted.put(new JSONObject().put("type", "function").put("function", function));
        }
        return converted.length() == 0 ? null : converted;
    }

    private static Object anthropicToolChoice(Object raw) throws GatewayException, JSONException {
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof JSONObject)) {
            throw new GatewayException(400, "invalid_tool_choice",
                    "Anthropic tool_choice must be an object");
        }
        JSONObject choice = (JSONObject) raw;
        String type = choice.optString("type", "auto").toLowerCase(Locale.US);
        if ("auto".equals(type)) return "auto";
        if ("any".equals(type)) return "required";
        if ("none".equals(type)) return "none";
        if ("tool".equals(type)) {
            String name = choice.optString("name", "").trim();
            if (name.length() == 0) {
                throw new GatewayException(400, "invalid_tool_choice",
                        "tool_choice.name is required when type=tool");
            }
            return new JSONObject().put("type", "function")
                    .put("function", new JSONObject().put("name", name));
        }
        throw new GatewayException(400, "invalid_tool_choice",
                "Unsupported Anthropic tool_choice: " + type);
    }

    private static boolean anthropicWebSearchRequested(JSONArray tools) {
        if (tools == null) return false;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String type = tool.optString("type", "").toLowerCase(Locale.US);
            if (type.startsWith("web_search_") || "web_search".equals(type)) return true;
        }
        return false;
    }

    private static CompletionRequest translateChat(String id, JSONObject body)
            throws GatewayException, JSONException {
        String requestedModel = requiredString(body, "model");
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            throw new GatewayException(400, "missing_messages", "messages must be a non-empty array");
        }
        if (body.optInt("n", 1) != 1) {
            throw new GatewayException(400, "unsupported_n", "Only n=1 is supported");
        }
        String basePrompt = messagesToPrompt(messages, null);
        JSONObject outputFormat = parseOutputTextFormat(body.opt("response_format"), true);
        JSONArray chatTools = body.optJSONArray("tools");
        Object toolChoice = body.opt("tool_choice");
        if (chatTools == null) {
            JSONArray legacy = body.optJSONArray("functions");
            if (legacy != null) {
                chatTools = new JSONArray();
                for (int i = 0; i < legacy.length(); i++) {
                    JSONObject function = legacy.optJSONObject(i);
                    if (function != null) {
                        chatTools.put(new JSONObject().put("type", "function")
                                .put("function", function));
                    }
                }
                if (toolChoice == null) toolChoice = body.opt("function_call");
            }
        }
        OpenAiToolBridge.Plan toolPlan = OpenAiToolBridge.chatPlan(
                chatTools, toolChoice,
                body.optBoolean("parallel_tool_calls", true));
        ToolHistory toolHistory = chatToolHistory(messages);
        String prompt = OpenAiToolBridge.addInstructions(
                addOutputFormatInstructions(basePrompt, outputFormat), toolPlan, id);
        ModelSpec spec = modelSpec(requestedModel, body);
        int max = positiveLimit(body, "max_completion_tokens",
                positiveLimit(body, "max_tokens", 0));
        return new CompletionRequest(id, requestedModel, spec.nativeModel, basePrompt, prompt,
                spec.reasoning, spec.search, max, toolPlan, null, false)
                .withToolHistory(toolHistory)
                .withClientSessionScope(clientSessionScope(body));
    }

    private static CompletionRequest translateResponses(String id, JSONObject body)
            throws GatewayException, JSONException {
        String requestedModel = requiredString(body, "model");
        Object input = body.opt("input");
        if (input == null || input == JSONObject.NULL) {
            throw new GatewayException(400, "missing_input", "input is required");
        }
        String instructions = body.optString("instructions", null);
        String inputPrompt;
        if (input instanceof String) {
            inputPrompt = messagesToPrompt(new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", input)), instructions);
        } else if (input instanceof JSONArray) {
            inputPrompt = responseInputToPrompt((JSONArray) input, instructions);
        } else {
            throw new GatewayException(400, "invalid_input", "input must be a string or array");
        }
        String previousId = body.optString("previous_response_id", "").trim();
        ResponseState previous = previousId.length() == 0 ? null : responseState(previousId);
        if (previousId.length() > 0 && previous == null) {
            throw new GatewayException(400, "previous_response_not_found",
                    "previous_response_id is unknown or expired: " + previousId);
        }
        String basePrompt = previous == null ? inputPrompt
                : previous.transcript + "\n\n" + inputPrompt;
        JSONObject text = body.optJSONObject("text");
        JSONObject outputFormat = parseOutputTextFormat(
                text == null ? null : text.opt("format"), false);
        ToolHistory toolHistory = new ToolHistory(previous);
        if (input instanceof JSONArray) {
            collectResponsesToolHistory((JSONArray) input, toolHistory);
        }
        OpenAiToolBridge.Plan toolPlan = OpenAiToolBridge.responsesPlan(
                body.optJSONArray("tools"), body.opt("tool_choice"),
                body.optBoolean("parallel_tool_calls", true));
        // Responses request options are not inherited through previous_response_id. In
        // particular, silently carrying a prior forced/required tool into a tool-output turn can
        // make a model repeat the completed call forever. A client that wants another tool call
        // must send its tools again, as Codex and the OpenAI SDK do.
        String prompt = OpenAiToolBridge.addInstructions(
                addOutputFormatInstructions(basePrompt, outputFormat), toolPlan, id);
        ModelSpec spec = modelSpec(requestedModel, body);
        int max = positiveLimit(body, "max_output_tokens", 0);
        return new CompletionRequest(id, requestedModel, spec.nativeModel, basePrompt, prompt,
                spec.reasoning,
                spec.search || nativeWebSearchRequested(body.optJSONArray("tools")),
                max, toolPlan,
                previousId.length() == 0 ? null : previousId, true)
                .withToolHistory(toolHistory)
                .withClientSessionScope(clientSessionScope(body))
                .withOutputTextFormat(outputFormat);
    }

    /** Normalize the two current OpenAI structured-output request shapes. */
    private static JSONObject parseOutputTextFormat(Object raw, boolean chat)
            throws GatewayException, JSONException {
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof JSONObject)) {
            throw new GatewayException(400, "invalid_response_format",
                    "response format must be an object");
        }
        JSONObject object = (JSONObject) raw;
        String type = object.optString("type", "text").trim().toLowerCase(Locale.US);
        if ("text".equals(type) || "json_object".equals(type)) {
            return new JSONObject().put("type", type);
        }
        if (!"json_schema".equals(type)) {
            throw new GatewayException(400, "unsupported_response_format",
                    "Unsupported response format: " + type);
        }
        JSONObject source = chat ? object.optJSONObject("json_schema") : object;
        // Accept the nested Chat shape on Responses too; several SDK adapters share one option.
        if (source == null) source = object.optJSONObject("json_schema");
        JSONObject schema = source == null ? null : source.optJSONObject("schema");
        String name = source == null ? "" : source.optString("name", "").trim();
        if (source == null || schema == null || name.length() == 0) {
            throw new GatewayException(400, "invalid_json_schema",
                    "json_schema format requires name and schema objects");
        }
        JSONObject normalized = new JSONObject().put("type", "json_schema")
                .put("name", name).put("schema", new JSONObject(schema.toString()))
                .put("strict", source.optBoolean("strict", false));
        String description = source.optString("description", "").trim();
        if (description.length() > 0) normalized.put("description", description);
        return normalized;
    }

    private static String addOutputFormatInstructions(String prompt, JSONObject format) {
        if (format == null || "text".equals(format.optString("type", "text"))) return prompt;
        StringBuilder out = new StringBuilder(prompt == null ? "" : prompt);
        if ("json_object".equals(format.optString("type", ""))) {
            out.append("\n\n[OUTPUT FORMAT]\nReturn exactly one valid JSON object. Do not use "
                    + "Markdown fences or add text before or after the JSON.\n[/OUTPUT FORMAT]");
        } else {
            out.append("\n\n[OUTPUT FORMAT]\nReturn exactly one valid JSON value matching this "
                    + "schema. Do not use Markdown fences or add prose outside the JSON:\n")
                    .append(format.optJSONObject("schema"))
                    .append("\n[/OUTPUT FORMAT]");
        }
        return out.toString();
    }

    /**
     * Claude Code includes its stable conversation UUID inside metadata.user_id. Combine that
     * identity with a fingerprint of the first user turn before it reaches logs or persistent
     * state. Current Claude Code rotates the UUID for /clear and /new; the root fingerprint is a
     * defensive boundary for older/wrapped clients that accidentally keep the old UUID after
     * clearing their transcript.
     */
    private static String clientSessionScope(JSONObject body) {
        if (body == null) return null;
        String raw = body.optString("_deekseep_client_session_id", "").trim();
        JSONObject metadata = body.optJSONObject("metadata");
        JSONObject clientMetadata = body.optJSONObject("client_metadata");
        if (raw.length() == 0 && metadata != null) {
            raw = firstNonBlank(metadata.optString("user_id", ""),
                    metadata.optString("thread_id", ""),
                    metadata.optString("session_id", ""));
        }
        if (raw.length() == 0 && clientMetadata != null) {
            // Codex 0.144.x owns these fields. thread_id is the conversation boundary while
            // session_id is a fallback for clients that omit the thread identifier.
            raw = firstNonBlank(clientMetadata.optString("thread_id", ""),
                    clientMetadata.optString("session_id", ""));
        }
        if (raw.length() == 0) raw = body.optString("user", "").trim();
        if (raw.length() == 0) raw = body.optString("session_id", "").trim();
        if (raw.length() == 0) raw = body.optString("sessionId", "").trim();
        if (raw.length() == 0) return null;
        // Claude Code's metadata includes account/device prefixes. Its stable conversation UUID
        // follows the final _session_ delimiter; using only that suffix avoids accidental scope
        // changes when unrelated metadata changes and mirrors claude-code-router's behavior.
        int session = raw.lastIndexOf("_session_");
        if (session >= 0 && session + 9 < raw.length()) {
            raw = raw.substring(session + 9).trim();
        }
        String root = clientConversationRoot(body);
        String material = root.length() == 0 ? "identity:" + raw
                : "identity:" + raw + "\u0000root:" + root;
        return opaqueScope(material);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && value.trim().length() > 0) return value.trim();
        }
        return "";
    }

    /** Best-effort stable root across later turns of one stateless API transcript. */
    private static String clientConversationRoot(JSONObject body) {
        if (body == null) return "";
        try {
            JSONArray messages = body.optJSONArray("messages");
            if (messages != null) {
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null || !"user".equalsIgnoreCase(
                            message.optString("role", ""))) continue;
                    String value = contentText(message.opt("content")).trim();
                    if (value.length() > 0) return value;
                }
            }
            Object input = body.opt("input");
            if (input instanceof String) return ((String) input).trim();
            if (input instanceof JSONArray) {
                JSONArray items = (JSONArray) input;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || !"user".equalsIgnoreCase(
                            item.optString("role", ""))) continue;
                    String value = contentText(item.opt("content")).trim();
                    if (value.length() > 0) return value;
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String opaqueScope(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(20);
            for (int i = 0; i < digest.length && i < 10; i++) {
                out.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static ToolHistory chatToolHistory(JSONArray messages) {
        ToolHistory history = new ToolHistory();
        if (messages == null) return history;
        HashMap<String, Boolean> repeatSafeById = new HashMap<String, Boolean>();
        // First collect calls so an output can be paired even if a client reorders items.
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !"assistant".equalsIgnoreCase(
                    message.optString("role", ""))) continue;
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null) continue;
            for (int c = 0; c < calls.length(); c++) {
                JSONObject call = calls.optJSONObject(c);
                if (call == null) continue;
                JSONObject function = call.optJSONObject("function");
                String id = call.optString("id", "").trim();
                String name = function == null ? call.optString("name", "")
                        : function.optString("name", "");
                String namespace = call.optString("namespace", null);
                Object arguments = function == null ? call.opt("arguments")
                        : function.opt("arguments");
                String signature = OpenAiToolBridge.signatureFor(
                        OpenAiToolBridge.FUNCTION,
                        namespace, name, arguments);
                if (id.length() > 0 && signature != null) {
                    history.knownCalls.put(id, signature);
                    repeatSafeById.put(id, OpenAiToolBridge.safeToRepeat(
                            OpenAiToolBridge.FUNCTION, namespace, name));
                }
            }
        }
        int mutationEpoch = 0;
        boolean userInstructionAfterLastTool = false;
        HashMap<String, Integer> safeCompletionEpoch = new HashMap<String, Integer>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "");
            if ("user".equalsIgnoreCase(role)) {
                Object content = message.opt("content");
                if (content != null && content != JSONObject.NULL
                        && String.valueOf(content).trim().length() > 0) {
                    userInstructionAfterLastTool = true;
                }
                continue;
            }
            if (!"tool".equalsIgnoreCase(role)) continue;
            String id = message.optString("tool_call_id", "").trim();
            String signature = history.knownCalls.get(id);
            if (signature != null && toolOutputSucceeded(message.opt("content"))) {
                history.completedCalls.add(signature);
                if (Boolean.TRUE.equals(repeatSafeById.get(id))) {
                    safeCompletionEpoch.put(signature, mutationEpoch);
                } else {
                    mutationEpoch++;
                }
                userInstructionAfterLastTool = false;
            }
        }
        for (Map.Entry<String, Integer> entry : safeCompletionEpoch.entrySet()) {
            if (userInstructionAfterLastTool || entry.getValue() < mutationEpoch) {
                history.repeatableCalls.add(entry.getKey());
            }
        }
        return history;
    }

    private static void collectResponsesToolHistory(JSONArray input, ToolHistory history) {
        if (input == null || history == null) return;
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "").toLowerCase(Locale.US);
            String kind;
            String name;
            Object arguments;
            if ("function_call".equals(type)) {
                kind = OpenAiToolBridge.FUNCTION;
                name = item.optString("name", "");
                arguments = item.opt("arguments");
            } else if ("custom_tool_call".equals(type)) {
                kind = OpenAiToolBridge.CUSTOM;
                name = item.optString("name", "");
                arguments = item.opt("input");
            } else if ("shell_call".equals(type) || "local_shell_call".equals(type)) {
                kind = OpenAiToolBridge.SHELL;
                name = OpenAiToolBridge.SHELL;
                arguments = item.has("action") ? item.opt("action") : item.opt("arguments");
            } else if ("apply_patch_call".equals(type)) {
                kind = OpenAiToolBridge.APPLY_PATCH;
                name = OpenAiToolBridge.APPLY_PATCH;
                arguments = item.has("operation") ? item.opt("operation") : item.opt("arguments");
            } else {
                continue;
            }
            String id = item.optString("call_id", item.optString("id", "")).trim();
            String signature = OpenAiToolBridge.signatureFor(kind,
                    item.optString("namespace", null), name, arguments);
            if (id.length() > 0 && signature != null) history.knownCalls.put(id, signature);
        }
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "").toLowerCase(Locale.US);
            if (!"function_call_output".equals(type)
                    && !"custom_tool_call_output".equals(type)
                    && !"shell_call_output".equals(type)
                    && !"local_shell_call_output".equals(type)
                    && !"apply_patch_call_output".equals(type)
                    && !"computer_call_output".equals(type)) continue;
            String id = item.optString("call_id", item.optString("id", "")).trim();
            String signature = history.knownCalls.get(id);
            if (signature != null && toolOutputSucceeded(item.opt("output"))) {
                history.completedCalls.add(signature);
            }
        }
    }

    private static boolean toolOutputSucceeded(Object output) {
        if (output == null || output == JSONObject.NULL) return true;
        if (output instanceof JSONObject) {
            JSONObject object = (JSONObject) output;
            if (object.has("success") && !object.optBoolean("success", true)) return false;
            if (object.has("ok") && !object.optBoolean("ok", true)) return false;
            String status = object.optString("status", "").toLowerCase(Locale.US);
            if ("error".equals(status) || "failed".equals(status)
                    || "failure".equals(status) || "cancelled".equals(status)) return false;
            Object error = object.opt("error");
            if (error != null && error != JSONObject.NULL
                    && String.valueOf(error).trim().length() > 0) return false;
            return true;
        }
        String text = String.valueOf(output).trim().toLowerCase(Locale.US);
        if (text.length() == 0) return true;
        if (text.startsWith("error:") || text.startsWith("failed:")
                || text.startsWith("failure:") || text.startsWith("permission denied")) {
            return false;
        }
        java.util.regex.Matcher exit = java.util.regex.Pattern.compile(
                "process exited with code\\s+(-?[0-9]+)").matcher(text);
        return !exit.find() || "0".equals(exit.group(1));
    }

    private static String responseInputToPrompt(JSONArray input, String instructions)
            throws GatewayException, JSONException {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            Object item = input.opt(i);
            if (item instanceof String) {
                messages.put(new JSONObject().put("role", "user").put("content", item));
                continue;
            }
            if (!(item instanceof JSONObject)) {
                throw new GatewayException(400, "invalid_input_item", "Every input item must be an object");
            }
            JSONObject obj = (JSONObject) item;
            String type = obj.optString("type", "message");
            if ("message".equals(type)) {
                messages.put(new JSONObject().put("role", obj.optString("role", "user"))
                        .put("content", obj.opt("content")));
            } else if ("function_call".equals(type) || "custom_tool_call".equals(type)
                    || "shell_call".equals(type) || "local_shell_call".equals(type)
                    || "apply_patch_call".equals(type)) {
                String callId = obj.optString("call_id", obj.optString("id", "unknown"));
                String name = obj.optString("name", type.replace("_call", ""));
                String namespace = obj.optString("namespace", "").trim();
                Object args = obj.has("arguments") ? obj.opt("arguments")
                        : (obj.has("input") ? obj.opt("input")
                        : (obj.has("action") ? obj.opt("action") : obj.opt("operation")));
                messages.put(new JSONObject().put("role", "assistant")
                        .put("content", "Requested tool "
                                + (namespace.length() == 0 ? "" : namespace + ".") + name
                                + " (call_id=" + callId
                                + ") with arguments:\n" + stringifyToolValue(args)));
            } else if ("function_call_output".equals(type)
                    || "custom_tool_call_output".equals(type)
                    || "shell_call_output".equals(type)
                    || "local_shell_call_output".equals(type)
                    || "apply_patch_call_output".equals(type)
                    || "computer_call_output".equals(type)) {
                String callId = obj.optString("call_id", "unknown");
                messages.put(new JSONObject().put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", stringifyToolValue(obj.opt("output"))));
            } else if ("agent_message".equals(type)) {
                String text = contentText(obj.opt("content"));
                if (text.trim().length() > 0) {
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", text));
                }
            } else if ("reasoning".equals(type)) {
                // Reasoning records can carry an opaque encrypted_content blob. Only the public
                // summary is useful to the local text model.
                Object summary = obj.opt("summary");
                if (summary != null && summary != JSONObject.NULL) {
                    String summaryText = contentText(summary);
                    if (summaryText.trim().length() == 0) continue;
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", summaryText));
                }
            } else if ("compaction".equals(type) || "context_compaction".equals(type)
                    || "compaction_trigger".equals(type) || "item_reference".equals(type)
                    || "additional_tools".equals(type)) {
                // These are Responses transport controls. Their opaque/encrypted payload must not
                // be treated as a user or tool message by a non-OpenAI upstream model.
            } else {
                // Preserve forward-compatible Agent/Codex items as textual context instead of
                // rejecting a whole turn when OpenAI adds a new item type. Do not serialize the
                // entire object because future items may contain opaque encrypted fields.
                String publicText = "";
                Object candidate = obj.has("output") ? obj.opt("output") : obj.opt("content");
                if (candidate instanceof String) publicText = ((String) candidate).trim();
                messages.put(new JSONObject().put("role", "tool")
                        .put("content", "Responses context item (type=" + type + ")"
                                + (publicText.length() == 0 ? "" : ":\n" + publicText)));
            }
        }
        return messagesToPrompt(messages, instructions);
    }

    private static String messagesToPrompt(JSONArray messages, String instructions)
            throws GatewayException {
        if (messages.length() == 1 && (instructions == null || instructions.length() == 0)) {
            JSONObject only = messages.optJSONObject(0);
            if (only != null && "user".equals(only.optString("role", "user"))) {
                return contentText(only.opt("content"));
            }
        }
        StringBuilder prompt = new StringBuilder();
        if (instructions != null && instructions.trim().length() > 0) {
            prompt.append("[developer instructions]\n").append(instructions.trim()).append("\n\n");
        }
        prompt.append("Continue the following conversation. Follow system/developer instructions, "
                + "answer the final user request, and do not repeat role labels, conversation "
                + "history, tool calls, or tool results. Generate only the next assistant turn.\n\n");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) {
                throw new GatewayException(400, "invalid_message", "Every message must be an object");
            }
            String role = message.optString("role", "user").toLowerCase(Locale.US);
            if (!"system".equals(role) && !"developer".equals(role) && !"user".equals(role)
                    && !"assistant".equals(role) && !"tool".equals(role)) {
                throw new GatewayException(400, "invalid_role", "Unsupported message role: " + role);
            }
            StringBuilder value = new StringBuilder(contentText(message.opt("content")));
            if ("assistant".equals(role)) {
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int c = 0; c < calls.length(); c++) {
                        JSONObject call = calls.optJSONObject(c);
                        if (call == null) continue;
                        JSONObject function = call.optJSONObject("function");
                        String namespace = call.optString("namespace", "").trim();
                        if (value.length() > 0) value.append('\n');
                        value.append("Requested tool ")
                                .append(namespace.length() == 0 ? "" : namespace + ".")
                                .append(function == null ? call.optString("name", "unknown")
                                        : function.optString("name", "unknown"))
                                .append(" (call_id=").append(call.optString("id", "unknown"))
                                .append(") with arguments:\n")
                                .append(function == null ? stringifyToolValue(call.opt("arguments"))
                                        : stringifyToolValue(function.opt("arguments")));
                    }
                }
                JSONObject legacyCall = message.optJSONObject("function_call");
                if (legacyCall != null) {
                    if (value.length() > 0) value.append('\n');
                    value.append("Requested tool ").append(legacyCall.optString("name", "unknown"))
                            .append(" with arguments:\n")
                            .append(stringifyToolValue(legacyCall.opt("arguments")));
                }
            } else if ("tool".equals(role)) {
                String callId = message.optString("tool_call_id", "unknown");
                value.insert(0, "Tool result for " + callId + ":\n");
            }
            prompt.append('[').append(role).append("]\n")
                    .append(value).append("\n\n");
        }
        prompt.append("[end conversation history]\nGenerate only the next assistant turn.");
        return prompt.toString().trim();
    }

    private static String contentText(Object content) throws GatewayException {
        if (content == null || content == JSONObject.NULL) return "";
        if (content instanceof String) return (String) content;
        if (!(content instanceof JSONArray)) return String.valueOf(content);
        JSONArray parts = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            Object part = parts.opt(i);
            if (part instanceof String) {
                if (text.length() > 0) text.append('\n');
                text.append(part);
                continue;
            }
            if (!(part instanceof JSONObject)) continue;
            JSONObject obj = (JSONObject) part;
            String type = obj.optString("type", "text");
            if ("text".equals(type) || "input_text".equals(type)
                    || "output_text".equals(type) || "summary_text".equals(type)) {
                if (text.length() > 0) text.append('\n');
                text.append(obj.optString("text", ""));
            } else if ("image_url".equals(type) || "input_image".equals(type)
                    || "input_file".equals(type)) {
                throw new GatewayException(400, "unsupported_multimodal_input",
                        "This gateway build currently accepts text input only");
            }
        }
        return text.toString();
    }

    private static String stringifyToolValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONObject || value instanceof JSONArray) return value.toString();
        return String.valueOf(value);
    }

    private static CompletionResult completeOpenAiRequest(CompletionRequest request,
                                                           DeltaSink sink) throws Exception {
        if (!request.toolsActive()) return backend.complete(request, sink);

        CompletionResult current = backend.complete(request, sink);
        StringBuilder reasoning = new StringBuilder(current.reasoning);
        boolean lastMalformed = false;
        boolean lastDeferred = false;

        // Keep repairs inside the original request. A client-side streaming fallback replays the
        // entire turn and can repeat side effects; a second bounded repair is both cheaper and
        // keeps Claude Code in one valid Anthropic message lifecycle.
        for (int repair = 0; repair <= 2; repair++) {
            List<OpenAiToolBridge.Call> calls = OpenAiToolBridge.parseCalls(
                    current.text, request.toolPlan);
            if (!calls.isEmpty()) {
                List<OpenAiToolBridge.Call> fresh = freshToolCalls(request, calls);
                if (!fresh.isEmpty()) {
                    return new CompletionResult("", reasoning.toString(), "tool_calls", fresh);
                }
                if (shouldSuppressCompletedCalls(request, calls)) {
                    return recoverFromDuplicateToolCall(request, reasoning.toString(), sink);
                }
            }

            lastMalformed = OpenAiToolBridge.resemblesCallSyntax(
                    current.text, request.toolPlan) || suppressedToolMarkup(sink);
            if (lastMalformed && hasAutoCompletedToolHistory(request)) {
                return recoverFromDuplicateToolCall(request, reasoning.toString(), sink);
            }
            lastDeferred = looksLikeDeferredAgentAction(request, current);
            boolean required = request.toolPlan.choice.requiresCall();
            if (!required && !lastMalformed && !lastDeferred) {
                return new CompletionResult(current.text, reasoning.toString(),
                        current.finishReason, current.toolCalls);
            }
            if (repair == 2) break;

            String issue = lastDeferred ? "deferred_action"
                    : (lastMalformed ? "malformed_tool" : "required_tool");
            apiLog("AGENT_TOOL_REPAIR request_id=" + request.requestId
                    + " attempt=" + (repair + 1) + " issue=" + issue
                    + " init=" + isPendingInitRequest(request));
            current = backend.complete(request.withPrompt(agentToolRepairPrompt(
                    request, current, lastDeferred, repair + 1)),
                    nextAnthropicGeneration(sink));
            reasoning.append(current.reasoning);
        }

        // If DeepSeek ignores both corrections, issue one harmless discovery action instead of
        // ending after a promise. The next Agent round consumes the real tool result and resumes
        // the unchanged user task. This fallback never mutates files or external state.
        List<OpenAiToolBridge.Call> continuation = requiresWorkspaceFileAction(request)
                ? Collections.<OpenAiToolBridge.Call>emptyList()
                : safeAgentContinuation(request);
        if (!continuation.isEmpty() && !request.toolPlan.choice.requiresCall()) {
            apiLog("AGENT_CONTINUATION_FALLBACK request_id=" + request.requestId
                    + " tool=" + continuation.get(0).name);
            return new CompletionResult("", reasoning.toString(),
                    "tool_calls", continuation);
        }
        if (lastDeferred) {
            throw new GatewayException(502, "agent_action_not_started", "server_error",
                    "DeepSeek announced an Agent action but did not emit a usable tool call");
        }
        throw new GatewayException(502, "tool_call_generation_failed", "server_error",
                "DeepSeek did not produce a valid required tool call after two repairs");
    }

    private static boolean suppressedToolMarkup(DeltaSink sink) {
        return sink instanceof AnthropicStreamEmitter
                && ((AnthropicStreamEmitter) sink).hasSuppressedToolMarkup();
    }

    private static String agentToolRepairPrompt(CompletionRequest request,
                                                 CompletionResult previous,
                                                 boolean deferred, int attempt) {
        String prior = previous == null || previous.text == null
                ? "" : previous.text.trim();
        if (prior.length() == 0 && previous != null && previous.reasoning != null) {
            prior = previous.reasoning.trim();
            if (prior.length() > 1400) prior = prior.substring(prior.length() - 1400);
            if (prior.length() > 0) prior = "[private reasoning tail]\n" + prior;
        }
        if (prior.length() > 3000) prior = prior.substring(0, 3000);

        OpenAiToolBridge.Definition preferred = requiresWorkspaceFileAction(request)
                ? preferredWorkspaceFileTool(request)
                : preferredRepairTool(request.toolPlan, prior);
        StringBuilder correction = new StringBuilder(request.prompt)
                .append(deferred ? "\n\n[AGENT ACTION REQUIRED]\n"
                        : "\n\n[TOOL FORMAT CORRECTION]\n")
                .append("Repair attempt ").append(attempt).append(". Your previous output would ")
                .append("end the Agent turn without a usable action. Do not narrate, explain, ")
                .append("simulate a result, or say what you will do. Return ONLY one complete ")
                .append("canonical <tool>{json}</tool> block for the next real action now.");
        if (preferred != null) {
            correction.append(" Use exactly tool ")
                    .append(JSONObject.quote(qualifiedToolName(preferred)))
                    .append(" and fill arguments with real values derived from the user's request. ")
                    .append("Its exact JSON argument schema is: ")
                    .append(preferred.schema.toString()).append('.');
        } else {
            correction.append(" Choose one tool from the request-scoped available-tools list and ")
                    .append("satisfy every required schema field with real values.");
        }
        correction.append(" Never use placeholders and never invent a tool result.\n")
                .append("Previous invalid output:\n").append(prior)
                .append(deferred ? "\n[/AGENT ACTION REQUIRED]"
                        : "\n[/TOOL FORMAT CORRECTION]");
        return correction.toString();
    }

    private static OpenAiToolBridge.Definition preferredRepairTool(
            OpenAiToolBridge.Plan plan, String prior) {
        if (plan == null || plan.tools.isEmpty()) return null;
        String lower = prior == null ? "" : prior.toLowerCase(Locale.US);
        for (OpenAiToolBridge.Definition tool : plan.tools) {
            String qualified = qualifiedToolName(tool).toLowerCase(Locale.US);
            if (lower.contains(qualified) || lower.contains(tool.name.toLowerCase(Locale.US))) {
                return tool;
            }
        }
        String[][] intentTools = new String[][] {
                {"web", "internet", "online", "网络", "联网", "websearch"},
                {"read", "open", "view", "读取", "查看", "打开", "read"},
                {"search", "find", "grep", "搜索", "查找", "检索", "grep"},
                {"list", "glob", "目录", "文件列表", "glob"},
                {"edit", "modify", "修改", "编辑", "edit"},
                {"write", "create file", "写入", "创建文件", "write"},
                {"run", "execute", "command", "build", "test", "package", "执行",
                        "命令", "构建", "测试", "打包", "bash"}
        };
        for (String[] mapping : intentTools) {
            boolean matches = false;
            for (int i = 0; i + 1 < mapping.length; i++) {
                if (lower.contains(mapping[i])) { matches = true; break; }
            }
            if (!matches) continue;
            String wanted = mapping[mapping.length - 1];
            for (OpenAiToolBridge.Definition tool : plan.tools) {
                if (tool.name.equalsIgnoreCase(wanted)) return tool;
            }
        }
        for (String wanted : new String[] {"Glob", "Read", "Grep", "Bash"}) {
            for (OpenAiToolBridge.Definition tool : plan.tools) {
                if (tool.name.equalsIgnoreCase(wanted)) return tool;
            }
        }
        return plan.tools.get(0);
    }

    private static String qualifiedToolName(OpenAiToolBridge.Definition tool) {
        return tool.namespace == null ? tool.name : tool.namespace + "." + tool.name;
    }

    private static List<OpenAiToolBridge.Call> safeAgentContinuation(
            CompletionRequest request) {
        if (request == null || request.toolPlan == null
                || !OpenAiToolBridge.Choice.AUTO.equals(request.toolPlan.choice.mode)) {
            return Collections.emptyList();
        }
        String[][] candidates = new String[][] {
                {"Glob", "{\"pattern\":\"*\"}"},
                {"Bash", "{\"command\":\"pwd\",\"description\":"
                        + "\"Anchor the current workspace before continuing\"}"}
        };
        for (String[] candidate : candidates) {
            String raw = "<tool>{\"name\":" + JSONObject.quote(candidate[0])
                    + ",\"arguments\":" + candidate[1] + "}</tool>";
            List<OpenAiToolBridge.Call> parsed = OpenAiToolBridge.parseCalls(
                    raw, request.toolPlan);
            if (!parsed.isEmpty()) return Collections.singletonList(parsed.get(0));
        }
        return Collections.emptyList();
    }

    private static boolean looksLikeDeferredAgentAction(CompletionRequest request,
                                                         CompletionResult result) {
        if (request == null || !request.toolsActive() || result == null
                || result.hasToolCalls()) return false;
        if (isPendingInitRequest(request)) return true;
        // A code block is not a completed workspace mutation. This conservative intent check is
        // based only on the final user turn, requires an actual file tool, and stops applying as
        // soon as a successful file mutation is present in the supplied tool history.
        if (requiresWorkspaceFileAction(request)) return true;
        String text = result.text == null ? "" : result.text.trim();
        if (text.length() > 4000) return false;
        if (looksLikeActionIntent(text, false)) return true;
        // DeepSeek sometimes puts the promised next action only in THINK and emits an empty
        // public answer. Claude Code then considers the Agent turn complete even though no tool
        // ran. Inspect only the tail in that exact empty-answer case; ordinary visible answers
        // may legitimately mention earlier plans and must not be rewritten.
        if (text.length() == 0 || "(no content)".equalsIgnoreCase(text)) {
            String reasoning = result.reasoning == null ? "" : result.reasoning.trim();
            if (reasoning.length() > 1800) {
                reasoning = reasoning.substring(reasoning.length() - 1800);
            }
            return looksLikeActionIntent(reasoning, true);
        }
        return false;
    }

    private static boolean requiresWorkspaceFileAction(CompletionRequest request) {
        if (request == null || request.toolPlan == null
                || !OpenAiToolBridge.hasWorkspaceFileTool(request.toolPlan)
                || hasCompletedWorkspaceFileMutation(request)) return false;
        String value = finalUserRequest(request).trim().toLowerCase(Locale.US)
                .replace('’', '\'');
        if (value.length() == 0) return false;
        String[] exclusions = new String[] {
                "示例", "例子", "代码片段", "只输出代码", "不要修改", "不要写入",
                "无需修改", "无需写入", "不用修改", "不用写入", "example", "snippet",
                "show me", "only output", "do not edit", "don't edit", "do not modify",
                "without changing", "without editing"
        };
        for (String exclusion : exclusions) if (value.contains(exclusion)) return false;
        String[] actions = new String[] {
                "创建", "新建", "写入", "写到", "写进", "保存为", "保存到", "编写",
                "实现", "修改", "编辑", "更新", "修复", "搭建", "做一个", "写一个", "写个",
                "create ", "write ", "save ", "implement ", "edit ", "modify ",
                "update ", "fix ", "build "
        };
        boolean action = false;
        for (String candidate : actions) {
            if (value.contains(candidate)) { action = true; break; }
        }
        if (!action) return false;
        String[] targets = new String[] {
                "文件", "项目", "仓库", "工作区", "代码", "脚本", "程序", "应用", "页面",
                "网页", "网站", "组件", "功能", "配置", "文档", "模块", "插件", "计算器",
                " file", "file ", "workspace", "repository", "repo", "project", " code",
                "script", "program", " app", "page", "component", "feature", "config",
                "document", "module", "plugin"
        };
        for (String target : targets) if (value.contains(target)) return true;
        return value.matches("(?s).*\\b[^\\s/]+\\.[a-z0-9]{1,12}\\b.*");
    }

    private static String finalUserRequest(CompletionRequest request) {
        String prompt = request == null || request.basePrompt == null ? "" : request.basePrompt;
        int marker = prompt.lastIndexOf("[user]\n");
        if (marker < 0) return prompt;
        int start = marker + "[user]\n".length();
        int end = prompt.indexOf("\n\n[", start);
        return end < 0 ? prompt.substring(start) : prompt.substring(start, end);
    }

    private static boolean hasCompletedWorkspaceFileMutation(CompletionRequest request) {
        if (request == null || request.completedToolCalls.isEmpty()) return false;
        String[] names = new String[] {
                "write", "edit", "multiedit", "notebookedit", "write_file",
                "create_file", "edit_file", "apply_patch", "str_replace_editor"
        };
        for (String signature : request.completedToolCalls) {
            if (signature == null) continue;
            String lower = signature.toLowerCase(Locale.US);
            for (String name : names) {
                if (lower.contains("\u0000" + name + "\u0000")) return true;
            }
        }
        return false;
    }

    private static OpenAiToolBridge.Definition preferredWorkspaceFileTool(
            CompletionRequest request) {
        if (request == null || request.toolPlan == null) return null;
        String user = finalUserRequest(request).toLowerCase(Locale.US);
        boolean editing = user.contains("修改") || user.contains("编辑")
                || user.contains("更新") || user.contains("修复")
                || user.contains("edit ") || user.contains("modify ")
                || user.contains("update ") || user.contains("fix ");
        String[] preferred = editing
                ? new String[] {"Edit", "apply_patch", "MultiEdit", "Write", "NotebookEdit"}
                : new String[] {"Write", "apply_patch", "Edit", "MultiEdit", "NotebookEdit"};
        for (String wanted : preferred) {
            for (OpenAiToolBridge.Definition tool : request.toolPlan.tools) {
                if (wanted.equalsIgnoreCase(tool.name)
                        || ("apply_patch".equalsIgnoreCase(wanted)
                                && OpenAiToolBridge.APPLY_PATCH.equals(tool.kind))) return tool;
            }
        }
        return null;
    }

    private static boolean looksLikeActionIntent(String value, boolean anywhere) {
        if (value == null || value.trim().length() == 0) return false;
        String lower = value.trim().toLowerCase(Locale.US).replace('’', '\'');
        String[] intents = new String[] {
                "i will ", "i'll ", "let me ", "next, i will ", "first, i will ",
                "i'll start", "i'll first", "i need to ", "i'm going to ",
                "now we need to ", "we need to ", "next we need to ",
                "我将", "我会", "我先", "我来", "我现在", "让我先", "接下来我",
                "下面我将", "首先，我将", "首先我将", "现在需要", "接着需要",
                "下一步需要"
        };
        int intentAt = -1;
        for (String intent : intents) {
            int found = anywhere ? lower.lastIndexOf(intent)
                    : (lower.startsWith(intent) ? 0 : -1);
            if (found > intentAt) intentAt = found;
        }
        if (intentAt < 0) return false;
        String tail = lower.substring(intentAt);
        if (tail.startsWith("i will not ") || tail.startsWith("i won't ")
                || tail.startsWith("我不会") || tail.startsWith("无需")
                || tail.startsWith("不需要")) return false;
        String[] actions = new String[] {
                " inspect", " read", " check", " run", " create", " write", " edit",
                " update", " test", " fix", " continue", " call", " execute", " search",
                " start", " use", " share", " list", " open", " analyze", " verify",
                " build", " package", "查看", "读取", "检查", "执行", "创建", "写入",
                "修改", "更新", "测试", "修复", "继续", "调用", "搜索", "启动", "使用",
                "分享", "列出", "打开", "分析", "验证", "构建", "打包", "完成"
        };
        String padded = " " + tail;
        for (String action : actions) if (padded.contains(action)) return true;
        return false;
    }

    private static boolean isPendingInitRequest(CompletionRequest request) {
        if (request == null || request.basePrompt == null
                || !request.basePrompt.contains(
                        "Please analyze this codebase and create a CLAUDE.md file")) return false;
        // A successful read proves an existing file, where upstream /init asks for suggestions;
        // a successful write/edit proves creation or improvement. Other reads still require work.
        return !hasCompletedToolForPath(request, "claude.md",
                new String[] {"Read", "Write", "Edit", "NotebookEdit"});
    }

    private static boolean hasCompletedToolForPath(CompletionRequest request, String path,
                                                   String[] names) {
        if (request == null || request.completedToolCalls.isEmpty()) return false;
        String wantedPath = path == null ? "" : path.toLowerCase(Locale.US);
        for (String signature : request.completedToolCalls) {
            if (signature == null) continue;
            String lower = signature.toLowerCase(Locale.US);
            if (wantedPath.length() > 0 && !lower.contains(wantedPath)) continue;
            for (String name : names) {
                if (name != null && lower.contains("\u0000" + name.toLowerCase(Locale.US)
                        + "\u0000")) return true;
            }
        }
        return false;
    }

    private static List<OpenAiToolBridge.Call> freshToolCalls(
            CompletionRequest request, List<OpenAiToolBridge.Call> calls) {
        if (calls == null || calls.isEmpty() || request == null
                || request.completedToolCalls.isEmpty()
                || request.toolPlan == null
                || !OpenAiToolBridge.Choice.AUTO.equals(request.toolPlan.choice.mode)) {
            return calls == null ? Collections.<OpenAiToolBridge.Call>emptyList() : calls;
        }
        ArrayList<OpenAiToolBridge.Call> fresh = new ArrayList<OpenAiToolBridge.Call>();
        int duplicate = 0;
        for (OpenAiToolBridge.Call call : calls) {
            String signature = OpenAiToolBridge.signatureFor(call);
            if (signature != null && request.completedToolCalls.contains(signature)
                    && !request.repeatableCompletedToolCalls.contains(signature)) {
                duplicate++;
            } else {
                fresh.add(call);
            }
        }
        if (duplicate > 0) {
            apiLog("TOOL_DUPLICATE_SUPPRESSED request_id=" + request.requestId
                    + " count=" + duplicate + " fresh=" + fresh.size());
        }
        return Collections.unmodifiableList(fresh);
    }

    private static boolean hasAutoCompletedToolHistory(CompletionRequest request) {
        return request != null && !request.completedToolCalls.isEmpty()
                && request.toolPlan != null
                && OpenAiToolBridge.Choice.AUTO.equals(request.toolPlan.choice.mode);
    }

    private static boolean shouldSuppressCompletedCalls(
            CompletionRequest request, List<OpenAiToolBridge.Call> calls) {
        if (request == null || calls == null || calls.isEmpty()
                || request.completedToolCalls.isEmpty() || request.toolPlan == null
                || !OpenAiToolBridge.Choice.AUTO.equals(request.toolPlan.choice.mode)) return false;
        for (OpenAiToolBridge.Call call : calls) {
            String signature = OpenAiToolBridge.signatureFor(call);
            if (signature == null || !request.completedToolCalls.contains(signature)
                    || request.repeatableCompletedToolCalls.contains(signature)) return false;
        }
        return true;
    }

    private static CompletionResult recoverFromDuplicateToolCall(
            CompletionRequest request, String priorReasoning, DeltaSink sink) throws Exception {
        String correction = request.prompt
                + "\n\n[DUPLICATE TOOL CORRECTION]\n"
                + "You attempted an exact tool call that already completed successfully. Its "
                + "real output is already present in the conversation. Do NOT emit that call "
                + "again. Consume the existing result and either answer the user now, or call "
                + "only a different tool or different arguments that are genuinely required.\n"
                + "[/DUPLICATE TOOL CORRECTION]";
        CompletionResult repaired = backend.complete(request.withPrompt(correction),
                nextAnthropicGeneration(sink));
        List<OpenAiToolBridge.Call> parsed = OpenAiToolBridge.parseCalls(
                repaired.text, request.toolPlan);
        if (!parsed.isEmpty()) {
            List<OpenAiToolBridge.Call> fresh = freshToolCalls(request, parsed);
            if (!fresh.isEmpty()) {
                return new CompletionResult("", priorReasoning + repaired.reasoning,
                        "tool_calls", fresh);
            }
        } else if (!OpenAiToolBridge.resemblesCallSyntax(
                repaired.text, request.toolPlan)) {
            return new CompletionResult(repaired.text, priorReasoning + repaired.reasoning,
                    repaired.finishReason);
        }

        // Last resort: remove the tool protocol from the final generation. This consumes one
        // more native call but prevents an already-successful action from executing repeatedly.
        String finalPrompt = request.basePrompt
                + "\n\n[FINAL ANSWER REQUIRED]\nAll successful tool outputs above are authoritative. "
                + "Do not request or simulate any tool. Complete the task now using those outputs "
                + "and return only the next assistant answer.\n[/FINAL ANSWER REQUIRED]";
        CompletionResult terminal = backend.complete(request.withPrompt(finalPrompt),
                nextAnthropicGeneration(sink));
        if (OpenAiToolBridge.resemblesCallSyntax(
                terminal.text, request.toolPlan)) {
            // A transport error here makes agent clients retry the entire turn, which asks for
            // the same side effect yet again. End the turn safely instead: the authoritative
            // successful result is already in the supplied conversation and no duplicate action
            // crosses the gateway.
            String fallback = "The requested tool action already completed successfully; "
                    + "the duplicate call was suppressed and was not executed again.";
            apiLog("TOOL_DUPLICATE_TERMINAL_FALLBACK request_id=" + request.requestId);
            DeltaSink fallbackSink = nextAnthropicGeneration(sink);
            if (fallbackSink != null && !fallbackSink.onText(fallback)) {
                throw new ClientDisconnectedIOException(
                        new IOException("client disconnected during duplicate fallback"));
            }
            return new CompletionResult(fallback,
                    priorReasoning + repaired.reasoning + terminal.reasoning, "stop");
        }
        return new CompletionResult(terminal.text,
                priorReasoning + repaired.reasoning + terminal.reasoning,
                terminal.finishReason);
    }

    private static DeltaSink nextAnthropicGeneration(DeltaSink sink) throws Exception {
        if (!(sink instanceof AnthropicStreamEmitter)) return null;
        ((AnthropicStreamEmitter) sink).nextGeneration();
        return sink;
    }

    private static String toolChoiceMode(CompletionRequest request) {
        return request == null || request.toolPlan == null || request.toolPlan.choice == null
                ? "none" : request.toolPlan.choice.mode;
    }

    private static JSONArray chatToolCalls(List<OpenAiToolBridge.Call> calls)
            throws JSONException {
        JSONArray array = new JSONArray();
        for (OpenAiToolBridge.Call call : calls) {
            array.put(new JSONObject().put("id", call.callId).put("type", "function")
                    .put("function", new JSONObject().put("name", call.name)
                            .put("arguments", call.arguments)));
        }
        return array;
    }

    private static String resultOutputForUsage(CompletionResult result) {
        if (result == null) return "";
        if (!result.hasToolCalls()) return result.text;
        StringBuilder value = new StringBuilder();
        for (OpenAiToolBridge.Call call : result.toolCalls) {
            value.append(call.name).append(call.arguments);
        }
        return value.toString();
    }

    private static void logToolCalls(String responseId, List<OpenAiToolBridge.Call> calls) {
        if (calls == null) return;
        for (OpenAiToolBridge.Call call : calls) {
            apiLog("TOOL_CALL response_id=" + responseId + " call_id=" + call.callId
                    + " type=" + call.kind + " name=" + call.name
                    + " namespace=" + (call.namespace == null ? "none" : call.namespace)
                    + " argument_chars=" + call.arguments.length());
        }
    }

    private static void writeBufferedChatText(OutputStream out, String id, String model,
                                              String value)
            throws IOException, JSONException {
        for (String chunk : stringChunks(value, 192)) {
            writeChatChunk(out, id, model, null, chunk, null);
        }
    }

    private static void writeChatToolCallChunks(OutputStream out, String id, String model,
                                                List<OpenAiToolBridge.Call> calls)
            throws IOException, JSONException {
        for (int index = 0; index < calls.size(); index++) {
            OpenAiToolBridge.Call call = calls.get(index);
            JSONObject function = new JSONObject().put("name", call.name).put("arguments", "");
            JSONObject tool = new JSONObject().put("index", index).put("id", call.callId)
                    .put("type", "function").put("function", function);
            writeChatDelta(out, id, model,
                    new JSONObject().put("tool_calls", new JSONArray().put(tool)), null);
            for (String arguments : stringChunks(call.arguments, 192)) {
                JSONObject continuation = new JSONObject().put("index", index)
                        .put("function", new JSONObject().put("arguments", arguments));
                writeChatDelta(out, id, model,
                        new JSONObject().put("tool_calls", new JSONArray().put(continuation)), null);
            }
        }
    }

    private static void writeChatDelta(OutputStream out, String id, String model,
                                       JSONObject delta, String finishReason)
            throws IOException, JSONException {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
        JSONObject chunk = new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", nowSeconds()).put("model", model)
                .put("choices", new JSONArray().put(choice));
        writeSseData(out, chunk.toString());
    }

    private static void writeChatUsageChunk(OutputStream out, String id, String model,
                                            JSONObject usage)
            throws IOException, JSONException {
        JSONObject chunk = new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", nowSeconds()).put("model", model)
                .put("choices", new JSONArray()).put("usage", usage);
        writeSseData(out, chunk.toString());
    }

    private static List<String> stringChunks(String value, int size) {
        if (value == null || value.length() == 0) return Collections.emptyList();
        ArrayList<String> chunks = new ArrayList<>();
        int start = 0;
        int limit = Math.max(16, size);
        while (start < value.length()) {
            int end = Math.min(value.length(), start + limit);
            if (end < value.length() && end > start
                    && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            if (end <= start) end = Math.min(value.length(), start + limit + 1);
            chunks.add(value.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static GatewayException asGatewayException(Throwable throwable) {
        Throwable value = throwable;
        for (int i = 0; value != null && i < 12; i++) {
            if (value instanceof GatewayException) return (GatewayException) value;
            value = value.getCause();
        }
        return new GatewayException(502, "upstream_error", "server_error",
                safeMessage(throwable));
    }

    private static ModelSpec modelSpec(String requested, JSONObject body) throws GatewayException {
        String lower = requested.toLowerCase(Locale.US);
        boolean reasoning = lower.contains("reasoner") || lower.contains("reasoning")
                || body.optBoolean("deep_think", false)
                || body.optBoolean("thinking_enabled", false)
                || body.optBoolean("enable_thinking", false);
        Object thinkingValue = body.opt("thinking");
        if (thinkingValue instanceof Boolean) reasoning = reasoning || (Boolean) thinkingValue;
        if (thinkingValue instanceof JSONObject) {
            JSONObject thinking = (JSONObject) thinkingValue;
            String type = thinking.optString("type", "");
            reasoning = reasoning || thinking.optBoolean("enabled", false)
                    || "enabled".equalsIgnoreCase(type)
                    || "on".equalsIgnoreCase(type)
                    || "adaptive".equalsIgnoreCase(type);
        }
        String reasoningEffort = body.optString("reasoning_effort", "");
        JSONObject reasoningObject = body.optJSONObject("reasoning");
        if (reasoningObject != null) {
            reasoningEffort = reasoningObject.optString("effort", reasoningEffort);
            reasoning = reasoning || reasoningObject.optBoolean("enabled", false);
        }
        if (reasoningEffort.length() > 0 && !"none".equalsIgnoreCase(reasoningEffort)) {
            reasoning = true;
        }
        boolean search = body.optBoolean("search", false);
        String nativeModel;
        if (lower.equals("deepseek-chat") || lower.equals("deepseek-aux")
                || lower.startsWith("deepseek-aux-") || lower.equals("deepseek-reasoner")
                || lower.equals("deepseek-v3") || lower.equals("default")
                || lower.equals("deepseek") || lower.startsWith("deepseek-r1")
                || lower.startsWith("gpt-") || lower.contains("codex")
                || lower.matches("o[1-9].*") || lower.startsWith("claude-")
                || lower.startsWith("sonnet") || lower.startsWith("opus")
                || lower.startsWith("haiku")) {
            nativeModel = "default";
        } else if (lower.equals("deepseek-expert") || lower.equals("expert")) {
            nativeModel = "expert";
        } else if (lower.equals("deepseek-vision") || lower.equals("vision")) {
            nativeModel = "vision";
        } else {
            throw new GatewayException(400, "model_not_found", "Unknown local model: " + requested);
        }
        return new ModelSpec(nativeModel, reasoning, search);
    }

    private static boolean nativeWebSearchRequested(JSONArray tools) {
        if (tools == null) return false;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            String type = tool.optString("type", "").toLowerCase(Locale.US);
            if (("web_search".equals(type) || "web_search_preview".equals(type))
                    && tool.optBoolean("external_web_access", true)) return true;
        }
        return false;
    }

    private static final class ModelSpec {
        final String nativeModel;
        final boolean reasoning;
        final boolean search;
        ModelSpec(String nativeModel, boolean reasoning, boolean search) {
            this.nativeModel = nativeModel;
            this.reasoning = reasoning;
            this.search = search;
        }
    }

    private static JSONObject modelsResponse() throws JSONException {
        JSONArray data = new JSONArray();
        data.put(modelObject("deepseek-chat", "default", false));
        data.put(modelObject("deepseek-reasoner", "default", true));
        data.put(modelObject("deepseek-expert", "expert", false));
        data.put(modelObject("deepseek-vision", "vision", false));
        // Codex chooses its built-in tool catalogue from the configured model name.
        // Keep the identity explicit: this name enables Codex compatibility metadata,
        // while requests still use DeepSeek Android's native default model.
        data.put(modelObject("gpt-5.4", "default", false)
                .put("alias_for", "deepseek-chat")
                .put("compatibility_alias", true));
        return new JSONObject().put("object", "list").put("data", data);
    }

    private static JSONObject modelObject(String id, String nativeModel, boolean reasoning)
            throws JSONException {
        return new JSONObject().put("id", id).put("object", "model")
                .put("created", 0).put("owned_by", "deepseek-android")
                .put("native_model", nativeModel).put("reasoning", reasoning);
    }

    private static JSONObject modelById(String id) throws JSONException {
        JSONArray models = modelsResponse().getJSONArray("data");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model != null && id.equals(model.optString("id", ""))) return model;
        }
        return null;
    }

    private static JSONObject responseObject(String id, String messageId,
                                             CompletionRequest request,
                                             CompletionResult result, String status,
                                             boolean includeOutput) throws JSONException {
        JSONObject response = new JSONObject();
        response.put("id", id);
        response.put("object", "response");
        response.put("created_at", nowSeconds());
        response.put("status", status);
        response.put("end_turn", "completed".equals(status) && !result.hasToolCalls());
        response.put("completed_at", "completed".equals(status) ? nowSeconds() : JSONObject.NULL);
        response.put("error", JSONObject.NULL);
        response.put("incomplete_details", JSONObject.NULL);
        response.put("instructions", JSONObject.NULL);
        response.put("model", request.requestedModel);
        JSONArray output = new JSONArray();
        if (includeOutput) {
            if (result.hasToolCalls()) {
                for (OpenAiToolBridge.Call call : result.toolCalls) {
                    output.put(responseToolItem(call,
                            "completed".equals(status) ? "completed" : status, true));
                }
            } else {
                output.put(outputMessage(messageId,
                        "completed".equals(status) ? "completed" : status, result.text));
            }
        }
        response.put("output", output);
        response.put("parallel_tool_calls", request.toolPlan == null
                || request.toolPlan.parallel);
        response.put("previous_response_id", request.previousResponseId == null
                ? JSONObject.NULL : request.previousResponseId);
        response.put("reasoning", new JSONObject().put("effort",
                request.reasoning ? "medium" : JSONObject.NULL).put("summary", JSONObject.NULL));
        response.put("store", false);
        response.put("temperature", JSONObject.NULL);
        response.put("text", new JSONObject().put("format",
                request.outputTextFormat == null
                        ? new JSONObject().put("type", "text")
                        : new JSONObject(request.outputTextFormat.toString())));
        response.put("tool_choice", responseToolChoice(request.toolPlan));
        response.put("tools", responseTools(request.toolPlan));
        response.put("top_p", JSONObject.NULL);
        response.put("truncation", "disabled");
        response.put("usage", includeOutput
                ? usage(request.prompt, resultOutputForUsage(result), result.reasoning)
                : JSONObject.NULL);
        response.put("metadata", new JSONObject());
        return response;
    }

    private static JSONObject outputMessage(String messageId, String status, String text)
            throws JSONException {
        JSONObject part = new JSONObject().put("type", "output_text")
                .put("text", text == null ? "" : text).put("annotations", new JSONArray());
        return new JSONObject().put("id", messageId).put("type", "message")
                .put("status", status).put("role", "assistant")
                .put("phase", "final_answer")
                .put("content", new JSONArray().put(part));
    }

    private static JSONObject responseToolItem(OpenAiToolBridge.Call call, String status,
                                               boolean includePayload) throws JSONException {
        JSONObject item = new JSONObject().put("id", call.itemId).put("call_id", call.callId);
        if (call.namespace != null) item.put("namespace", call.namespace);
        if (OpenAiToolBridge.CUSTOM.equals(call.kind)) {
            return item.put("type", "custom_tool_call").put("name", call.name)
                    .put("status", status)
                    .put("input", includePayload ? call.customInput() : "");
        }
        if (OpenAiToolBridge.SHELL.equals(call.kind)) {
            JSONObject action;
            try { action = new JSONObject(call.arguments); }
            catch (Throwable ignored) { action = new JSONObject(); }
            if (!action.has("commands") && action.has("command")) {
                action.put("commands", new JSONArray().put(action.optString("command", "")));
                action.remove("command");
            }
            return item.put("type", "shell_call").put("status", status)
                    .put("action", action);
        }
        if (OpenAiToolBridge.APPLY_PATCH.equals(call.kind)) {
            JSONObject operation;
            try { operation = new JSONObject(call.arguments); }
            catch (Throwable ignored) { operation = new JSONObject(); }
            return item.put("type", "apply_patch_call").put("status", status)
                    .put("operation", operation);
        }
        return item.put("type", "function_call").put("status", status)
                .put("name", call.name)
                .put("arguments", includePayload ? call.arguments : "");
    }

    private static JSONArray responseTools(OpenAiToolBridge.Plan plan) throws JSONException {
        JSONArray tools = new JSONArray();
        if (plan == null) return tools;
        java.util.HashSet<String> emitted = new java.util.HashSet<String>();
        for (OpenAiToolBridge.Definition definition : plan.tools) {
            String serialized = definition.original.toString();
            if (emitted.add(serialized)) tools.put(new JSONObject(serialized));
        }
        return tools;
    }

    private static Object responseToolChoice(OpenAiToolBridge.Plan plan) throws JSONException {
        if (plan == null || plan.choice == null) return "auto";
        if (!OpenAiToolBridge.Choice.FORCED.equals(plan.choice.mode)) return plan.choice.mode;
        JSONObject forced = new JSONObject().put("type", plan.choice.forcedKind)
                .put("name", plan.choice.forcedName);
        if (plan.choice.forcedNamespace != null) {
            forced.put("namespace", plan.choice.forcedNamespace);
        }
        return forced;
    }

    private static ResponseState responseState(String id) {
        purgeResponseStates();
        ResponseState state = RESPONSE_STATES.get(id);
        if (state == null) return null;
        if (System.currentTimeMillis() - state.createdAt > RESPONSE_STATE_TTL_MS) {
            RESPONSE_STATES.remove(id, state);
            return null;
        }
        return state;
    }

    private static void rememberResponseState(String id, CompletionRequest request,
                                              CompletionResult result) {
        if (id == null || request == null || result == null) return;
        StringBuilder transcript = new StringBuilder(request.basePrompt == null
                ? "" : request.basePrompt);
        transcript.append("\n\n[assistant]\n");
        if (result.hasToolCalls()) {
            for (OpenAiToolBridge.Call call : result.toolCalls) {
                transcript.append("Requested tool ")
                        .append(call.namespace == null ? "" : call.namespace + ".")
                        .append(call.name)
                        .append(" (call_id=").append(call.callId)
                        .append(", type=").append(call.kind).append(") with arguments:\n")
                        .append(call.arguments).append('\n');
            }
        } else {
            transcript.append(result.text);
        }
        HashMap<String, String> knownCalls = new HashMap<String, String>(
                request.knownToolCalls);
        for (OpenAiToolBridge.Call call : result.toolCalls) {
            String signature = OpenAiToolBridge.signatureFor(call);
            if (signature != null) knownCalls.put(call.callId, signature);
        }
        RESPONSE_STATES.put(id, new ResponseState(transcript.toString(),
                System.currentTimeMillis(), knownCalls, request.completedToolCalls));
        purgeResponseStates();
    }

    private static void purgeResponseStates() {
        long cutoff = System.currentTimeMillis() - RESPONSE_STATE_TTL_MS;
        String oldestId = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, ResponseState> entry : RESPONSE_STATES.entrySet()) {
            ResponseState state = entry.getValue();
            if (state == null || state.createdAt < cutoff) {
                RESPONSE_STATES.remove(entry.getKey(), state);
            } else if (state.createdAt < oldest) {
                oldest = state.createdAt;
                oldestId = entry.getKey();
            }
        }
        while (RESPONSE_STATES.size() > MAX_RESPONSE_STATES && oldestId != null) {
            RESPONSE_STATES.remove(oldestId);
            oldestId = null;
            oldest = Long.MAX_VALUE;
            for (Map.Entry<String, ResponseState> entry : RESPONSE_STATES.entrySet()) {
                if (entry.getValue().createdAt < oldest) {
                    oldest = entry.getValue().createdAt;
                    oldestId = entry.getKey();
                }
            }
        }
    }

    private static JSONObject anthropicMessageObject(String id, CompletionRequest request,
                                                     CompletionResult result)
            throws JSONException {
        JSONArray content = new JSONArray();
        if (result.reasoning.length() > 0) {
            content.put(new JSONObject().put("type", "thinking")
                    .put("thinking", result.reasoning)
                    .put("signature", reasoningSignature(result.reasoning)));
        }
        if (result.text.length() > 0 || !result.hasToolCalls()) {
            content.put(new JSONObject().put("type", "text").put("text", result.text));
        }
        for (OpenAiToolBridge.Call call : result.toolCalls) {
            JSONObject input;
            try { input = new JSONObject(call.arguments); }
            catch (Throwable ignored) { input = new JSONObject(); }
            content.put(new JSONObject().put("type", "tool_use")
                    .put("id", anthropicToolId(call)).put("name", call.name)
                    .put("input", input));
        }
        return new JSONObject().put("id", id).put("type", "message")
                .put("role", "assistant").put("model", request.requestedModel)
                .put("content", content).put("stop_reason", anthropicStopReason(result))
                .put("stop_sequence", JSONObject.NULL)
                .put("usage", anthropicUsage(request.prompt,
                        resultOutputForUsage(result), result.reasoning));
    }

    private static String anthropicStopReason(CompletionResult result) {
        if (result != null && result.hasToolCalls()) return "tool_use";
        String reason = result == null ? "" : result.finishReason;
        if ("length".equals(reason) || "max_tokens".equals(reason)) return "max_tokens";
        if ("stop_sequence".equals(reason)) return "stop_sequence";
        if ("refusal".equals(reason)) return "refusal";
        return "end_turn";
    }

    private static String anthropicToolId(OpenAiToolBridge.Call call) {
        if (call == null || call.callId == null) return "toolu_" + compactUuid();
        return call.callId.startsWith("call_")
                ? "toolu_" + call.callId.substring("call_".length()) : call.callId;
    }

    private static String reasoningSignature(String reasoning) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((reasoning == null ? "" : reasoning)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(value.length * 2);
            for (byte item : value) hex.append(String.format(Locale.US, "%02x", item & 0xff));
            return "deekseep-local-" + hex;
        } catch (Throwable ignored) {
            return "deekseep-local";
        }
    }

    private static JSONObject anthropicUsage(String prompt, String text, String reasoning)
            throws JSONException {
        int input = approximateTokens(prompt);
        int output = approximateTokens((text == null ? "" : text)
                + (reasoning == null ? "" : reasoning));
        return new JSONObject().put("input_tokens", input).put("output_tokens", output)
                .put("cache_creation_input_tokens", 0).put("cache_read_input_tokens", 0);
    }

    private static JSONObject usage(String prompt, String text, String reasoning)
            throws JSONException {
        int input = approximateTokens(prompt);
        int output = approximateTokens((text == null ? "" : text)
                + (reasoning == null ? "" : reasoning));
        return new JSONObject().put("input_tokens", input).put("output_tokens", output)
                .put("total_tokens", input + output)
                .put("input_tokens_details", new JSONObject().put("cached_tokens", 0))
                .put("output_tokens_details", new JSONObject()
                        .put("reasoning_tokens", approximateTokens(reasoning)));
    }

    private static JSONObject chatUsage(String prompt, String text, String reasoning)
            throws JSONException {
        int input = approximateTokens(prompt);
        int output = approximateTokens((text == null ? "" : text)
                + (reasoning == null ? "" : reasoning));
        return new JSONObject().put("prompt_tokens", input)
                .put("completion_tokens", output)
                .put("total_tokens", input + output)
                .put("prompt_tokens_details", new JSONObject().put("cached_tokens", 0))
                .put("completion_tokens_details", new JSONObject()
                        .put("reasoning_tokens", approximateTokens(reasoning)));
    }

    private static int approximateTokens(String value) {
        if (value == null || value.length() == 0) return 0;
        int ascii = 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 128) ascii++;
        int nonAscii = value.length() - ascii;
        return Math.max(1, (ascii + 3) / 4 + nonAscii);
    }

    private static void writeChatChunk(OutputStream out, String id, String model,
                                       String role, String content, String finishReason)
            throws IOException, JSONException {
        JSONObject delta = new JSONObject();
        if (role != null) delta.put("role", role);
        if (content != null) delta.put("content", content);
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", finishReason == null ? JSONObject.NULL : finishReason);
        JSONObject chunk = new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", nowSeconds()).put("model", model)
                .put("choices", new JSONArray().put(choice));
        writeSseData(out, chunk.toString());
    }

    private static void writeChatReasoningChunk(OutputStream out, String id, String model,
                                                String reasoning)
            throws IOException, JSONException {
        JSONObject delta = new JSONObject().put("reasoning_content", reasoning);
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta)
                .put("finish_reason", JSONObject.NULL);
        JSONObject chunk = new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", nowSeconds()).put("model", model)
                .put("choices", new JSONArray().put(choice));
        writeSseData(out, chunk.toString());
    }

    private static void writeResponseEvent(OutputStream out, String type, int sequence,
                                           JSONObject fields)
            throws IOException, JSONException {
        JSONObject event = new JSONObject();
        event.put("type", type);
        event.put("sequence_number", sequence);
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            event.put(key, fields.opt(key));
        }
        String frame = "event: " + type + "\ndata: " + event.toString() + "\n\n";
        writeSseFrame(out, frame);
    }

    private static void writeAnthropicEvent(OutputStream out, String type, JSONObject fields)
            throws IOException, JSONException {
        JSONObject event = new JSONObject().put("type", type);
        if (fields != null) {
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                event.put(key, fields.opt(key));
            }
        }
        writeSseFrame(out, "event: " + type + "\ndata: " + event.toString() + "\n\n");
    }

    private static void writeSseHeaders(OutputStream out) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache, no-transform\r\n"
                + "Connection: close\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        synchronized (out) {
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    private static void writeSseData(OutputStream out, String data) throws IOException {
        writeSseFrame(out, "data: " + data + "\n\n");
    }

    private static void writeSseFrame(OutputStream out, String frame) throws IOException {
        try {
            synchronized (out) {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            throw new ClientDisconnectedIOException(e);
        }
    }

    private static final class ClientDisconnectedIOException extends IOException {
        ClientDisconnectedIOException(Throwable cause) {
            super("local SSE client disconnected", cause);
        }
    }

    private static final class EmptyConnectionIOException extends IOException {
        EmptyConnectionIOException() { super("connection closed before HTTP request"); }
    }

    private static final class SseHeartbeat {
        private final OutputStream output;
        private final String protocol;
        private final String requestId;
        private volatile boolean stopped;
        private volatile boolean disconnected;
        private final StringBuilder anthropicOutput = new StringBuilder();
        private volatile int anthropicOutputTokens;
        private volatile int lastReportedAnthropicTokens = -1;
        private volatile AnthropicStreamEmitter anthropicEmitter;
        private volatile boolean started;
        private Thread thread;

        SseHeartbeat(OutputStream output, String protocol, String requestId) {
            this.output = output;
            this.protocol = protocol;
            this.requestId = requestId;
        }

        void attach(AnthropicStreamEmitter emitter) {
            anthropicEmitter = emitter;
        }

        synchronized void start() {
            if (started || stopped) return;
            started = true;
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    while (!stopped) {
                        try { Thread.sleep(SSE_HEARTBEAT_MS); }
                        catch (InterruptedException ignored) { return; }
                        if (stopped) return;
                        try {
                            if (PROTOCOL_ANTHROPIC.equals(protocol)) {
                                // Claude Code filters ping from its visible state. Pair it with
                                // cumulative usage, then restore thinking until text begins.
                                reportAnthropicUsage(false);
                            } else {
                                writeSseFrame(output, ": deekseep-ping\n\n");
                            }
                        } catch (Throwable t) {
                            disconnected = true;
                            apiLog("SSE_HEARTBEAT_DISCONNECTED protocol=" + protocol
                                    + " id=" + requestId);
                            return;
                        }
                    }
                }
            }, "Deekseep-SSE-Heartbeat");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            stopped = true;
            Thread value = thread;
            if (value != null) value.interrupt();
        }

        void addAnthropicOutput(String value) {
            if (!PROTOCOL_ANTHROPIC.equals(protocol)
                    || value == null || value.length() == 0) return;
            synchronized (anthropicOutput) {
                anthropicOutput.append(value);
                anthropicOutputTokens = approximateTokens(anthropicOutput.toString());
            }
        }

        private void reportAnthropicUsage(boolean onlyWhenAdvanced)
                throws IOException, JSONException {
            int current = anthropicOutputTokens;
            if (onlyWhenAdvanced && current <= lastReportedAnthropicTokens) return;
            AnthropicStreamEmitter emitter = anthropicEmitter;
            if (emitter == null) {
                writeAnthropicEvent(output, "ping", new JSONObject());
                writeAnthropicActivityEvent(output, current);
            } else {
                emitter.onAnthropicActivity(current);
            }
            lastReportedAnthropicTokens = Math.max(lastReportedAnthropicTokens, current);
        }

        int anthropicOutputTokens() { return anthropicOutputTokens; }

        boolean isDisconnected() { return disconnected; }
    }

    private static boolean isClientDisconnect(Throwable throwable) {
        Throwable value = throwable;
        for (int i = 0; value != null && i < 12; i++) {
            if (value instanceof ClientDisconnectedIOException) return true;
            value = value.getCause();
        }
        return false;
    }

    private static void ensureBackendReady() throws GatewayException {
        Backend b = backend;
        if (b == null || !b.isReady()) {
            throw new GatewayException(503, "host_not_ready", "server_error",
                    b == null ? "DeepSeek backend is not installed"
                            : "DeepSeek backend is not ready: " + b.readinessDetail());
        }
    }

    private static void requireProtocol(String expected) throws GatewayException {
        if (expected.equals(protocolMode())) return;
        String selected = PROTOCOL_ANTHROPIC.equals(protocolMode())
                ? "Anthropic" : "OpenAI";
        throw new GatewayException(409, "protocol_mismatch", "invalid_request_error",
                "This route is disabled because the API service is currently set to "
                        + selected + " format");
    }

    private static void requireAuth(HttpRequest request) throws GatewayException {
        String auth = request.headers.get("authorization");
        String supplied = null;
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            supplied = auth.substring(7).trim();
        }
        if ((supplied == null || supplied.length() == 0)
                && request.headers.containsKey("x-api-key")) {
            supplied = request.headers.get("x-api-key").trim();
        }
        String expected = apiKey;
        if (expected == null || supplied == null || !constantTimeEquals(expected, supplied)) {
            throw new GatewayException(401, "invalid_api_key", "authentication_error",
                    "Missing or invalid local API key; copy the current key again from "
                            + "the local API settings");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        try {
            return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                    right.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return left.equals(right);
        }
    }

    private static HttpRequest readRequest(InputStream input) throws IOException, GatewayException {
        int headerBytes = 0;
        String requestLine = readLine(input, MAX_HEADER_BYTES);
        if (requestLine == null) {
            throw new EmptyConnectionIOException();
        }
        if (requestLine.length() == 0) {
            throw new GatewayException(400, "empty_request", "Empty HTTP request");
        }
        headerBytes += requestLine.length();
        String[] first = requestLine.split(" ");
        if (first.length < 2) throw new GatewayException(400, "bad_request_line", "Invalid HTTP request line");
        String method = first[0].toUpperCase(Locale.US);
        String path = first[1];
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        Map<String, String> headers = new HashMap<String, String>();
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES - headerBytes);
            if (line == null || line.length() == 0) break;
            headerBytes += line.length();
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new GatewayException(431, "headers_too_large", "HTTP headers exceed 32 KiB");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                    line.substring(colon + 1).trim());
        }
        int length = 0;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null && lengthHeader.length() > 0) {
            try { length = Integer.parseInt(lengthHeader); }
            catch (NumberFormatException e) {
                throw new GatewayException(400, "invalid_content_length", "Invalid Content-Length");
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) {
            throw new GatewayException(413, "request_too_large", "Request body exceeds 1 MiB");
        }
        byte[] body;
        String transferEncoding = headers.get("transfer-encoding");
        if (transferEncoding != null
                && transferEncoding.toLowerCase(Locale.US).contains("chunked")) {
            body = readChunkedBody(input);
        } else {
            body = new byte[length];
            int offset = 0;
            while (offset < length) {
                int n = input.read(body, offset, length - offset);
                if (n < 0) throw new IOException("request body ended early");
                offset += n;
            }
        }
        return new HttpRequest(method, path, headers, body);
    }

    private static byte[] readChunkedBody(InputStream input) throws IOException, GatewayException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input, 128);
            if (sizeLine == null) throw new IOException("chunk size is missing");
            int extension = sizeLine.indexOf(';');
            String token = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            int size;
            try { size = Integer.parseInt(token, 16); }
            catch (NumberFormatException e) {
                throw new GatewayException(400, "invalid_chunk_size", "Invalid chunked body");
            }
            if (size < 0 || body.size() + size > MAX_BODY_BYTES) {
                throw new GatewayException(413, "request_too_large",
                        "Request body exceeds 1 MiB");
            }
            if (size == 0) {
                // Consume optional trailer headers.
                while (true) {
                    String trailer = readLine(input, MAX_HEADER_BYTES);
                    if (trailer == null || trailer.length() == 0) break;
                }
                break;
            }
            byte[] chunk = new byte[size];
            int offset = 0;
            while (offset < size) {
                int n = input.read(chunk, offset, size - offset);
                if (n < 0) throw new IOException("chunk body ended early");
                offset += n;
            }
            body.write(chunk);
            String ending = readLine(input, 4);
            if (ending == null || ending.length() != 0) {
                throw new GatewayException(400, "invalid_chunk_ending", "Invalid chunk terminator");
            }
        }
        return body.toByteArray();
    }

    private static String readLine(InputStream input, int remaining) throws IOException {
        if (remaining <= 0) throw new IOException("header limit reached");
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() < remaining) {
            int value = input.read();
            if (value < 0) break;
            if (previous == '\r' && value == '\n') {
                byte[] raw = line.toByteArray();
                return new String(raw, 0, Math.max(0, raw.length - 1),
                        StandardCharsets.ISO_8859_1);
            }
            line.write(value);
            previous = value;
        }
        if (line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1).trim();
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> headers;
        final byte[] body;
        HttpRequest(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static void writeJson(OutputStream output, int status, JSONObject json)
            throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static void writeEmpty(OutputStream output, int status, String reason)
            throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type, X-API-Key, "
                + "Anthropic-Version, Anthropic-Beta\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeError(OutputStream output, int status, String type,
                                   String code, String message) throws IOException {
        try { writeJson(output, status, errorObject(type, code, message)); }
        catch (JSONException e) { throw new IOException(e); }
    }

    private static void writeAnthropicError(OutputStream output, int status, String type,
                                            String message) throws IOException {
        try {
            JSONObject root = new JSONObject().put("type", "error")
                    .put("error", new JSONObject().put("type", type)
                            .put("message", message == null ? "Unknown error" : message))
                    .put("request_id", "req_" + compactUuid());
            writeJson(output, status, root);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String anthropicErrorType(GatewayException error) {
        if (error == null) return "api_error";
        if (error.status == 401) return "authentication_error";
        if (error.status == 403) return "permission_error";
        if (error.status == 404) return "not_found_error";
        if (error.status == 413) return "request_too_large";
        if (error.status == 429) return "rate_limit_error";
        if (error.status >= 500) return "api_error";
        return "invalid_request_error";
    }

    private static JSONObject errorObject(String type, String code, String message)
            throws JSONException {
        JSONObject error = new JSONObject().put("message", message == null ? "Unknown error" : message)
                .put("type", type).put("param", JSONObject.NULL).put("code", code);
        return new JSONObject().put("error", error);
    }

    private static void writeBusyAndClose(Socket socket) {
        try { writeError(socket.getOutputStream(), 503, "server_error",
                "gateway_busy", "Local API worker queue is full"); }
        catch (Throwable ignored) {}
        closeQuietly(socket);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            case 504: return "Gateway Timeout";
            case 500: return "Internal Server Error";
            default: return "Internal Server Error";
        }
    }

    private static String requiredString(JSONObject body, String key) throws GatewayException {
        String value = body.optString(key, "").trim();
        if (value.length() == 0) throw new GatewayException(400, "missing_" + key, key + " is required");
        return value;
    }

    private static int positiveLimit(JSONObject body, String key, int fallback)
            throws GatewayException {
        if (!body.has(key) || body.isNull(key)) return fallback;
        int value = body.optInt(key, -1);
        if (value <= 0) throw new GatewayException(400, "invalid_" + key, key + " must be positive");
        return value;
    }

    private static long nowSeconds() { return System.currentTimeMillis() / 1000L; }

    private static String compactUuid() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String loadOrCreateKey(Context context) {
        File file = new File(context.getFilesDir(), "deekseep_local_api_key");
        String existing = readPrivateText(file);
        if (isValidStoredKey(existing)) {
            return existing;
        }
        String generated = generateKey();
        writePrivateText(file, generated);
        return generated;
    }

    private static String loadProtocolMode(Context context) {
        String stored = readPrivateText(new File(context.getFilesDir(), PROTOCOL_FILE_NAME));
        return PROTOCOL_ANTHROPIC.equalsIgnoreCase(stored)
                ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    private static boolean isValidStoredKey(String value) {
        if (value == null || value.length() < 8 || value.length() > 256) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch <= 0x20 || ch >= 0x7f) return false;
        }
        return true;
    }

    private static String generateKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return "sk-deekseep-" + Base64.encodeToString(random,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String firstLanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            String bestAddress = null;
            String bestInterface = null;
            int bestPriority = Integer.MAX_VALUE;
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (network == null || !network.isUp() || network.isLoopback()) continue;
                String interfaceName = network.getName();
                int priority = lanInterfacePriority(interfaceName);
                if (priority < 0) continue;
                java.util.Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address == null || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()
                            || !(address instanceof java.net.Inet4Address)) continue;
                    String host = address.getHostAddress();
                    if (!isPrivateIpv4(host)) continue;
                    boolean better = betterLanCandidate(priority, interfaceName,
                            bestPriority, bestInterface);
                    if (better) {
                        bestPriority = priority;
                        bestInterface = String.valueOf(interfaceName);
                        bestAddress = host;
                    }
                }
            }
            return bestAddress;
        } catch (Throwable ignored) {}
        return null;
    }

    private static int lanInterfacePriority(String name) {
        String value = name == null ? "" : name.toLowerCase(Locale.US);
        if (value.startsWith("tun") || value.startsWith("vpn")
                || value.startsWith("rmnet")) return -1;
        if (value.startsWith("wlan") || value.startsWith("wifi")) return 0;
        if (value.startsWith("ap")) return 1;
        if (value.startsWith("eth")) return 2;
        return 10;
    }

    private static boolean betterLanCandidate(int candidatePriority, String candidateName,
                                              int currentPriority, String currentName) {
        return candidatePriority < currentPriority
                || (candidatePriority == currentPriority && (currentName == null
                || String.valueOf(candidateName).compareTo(currentName) < 0));
    }

    private static boolean isPrivateIpv4(String address) {
        if (address == null) return false;
        String[] parts = address.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 10 || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String readPrivateText(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > 4096) return null;
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            in.close();
            return new String(data, 0, offset, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) { return null; }
    }

    private static void writePrivateText(File file, String value) {
        if (file == null || value == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(file, false);
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Throwable t) { apiLog("KEY_WRITE_FAILED " + safeMessage(t)); }
    }

    private static void writeConnectionInfo(boolean active, String note) {
        boolean anthropic = PROTOCOL_ANTHROPIC.equals(protocolMode);
        String lanRoot = lanRootEndpoint();
        String text = "Local API\n"
                + "status=" + (active ? "running" : "stopped") + "\n"
                + "protocol=" + protocolMode() + "\n"
                + "base_url=" + endpoint() + "\n"
                + "lan_base_url=" + (lanRoot == null ? "unavailable"
                        : (anthropic ? lanRoot : lanRoot + "/v1")) + "\n"
                + "api_key=" + (apiKey == null ? "not-generated" : apiKey) + "\n"
                + (anthropic
                        ? "messages=" + rootEndpoint() + "/v1/messages\n"
                                + "count_tokens=" + rootEndpoint()
                                + "/v1/messages/count_tokens\n"
                        : "chat_completions=" + openAiEndpoint() + "/chat/completions\n"
                                + "responses=" + openAiEndpoint() + "/responses\n"
                                + "models=" + openAiEndpoint() + "/models\n")
                + "note=" + (note == null ? "authenticated-lan-enabled" : note) + "\n";
        writePath(INFO_FILE, text, false);
        writePath(PUBLIC_INFO_FILE, text, false);
    }

    private static synchronized void apiLog(String message) {
        String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new java.util.Date()) + "  " + message + "\n";
        try {
            File current = new File(LOG_FILE);
            if (current.isFile() && current.length() > 2L * 1024L * 1024L) {
                File previous = new File(LOG_FILE + ".1");
                if (previous.exists()) previous.delete();
                current.renameTo(previous);
            }
        } catch (Throwable ignored) {}
        writePath(LOG_FILE, line, true);
        try { Main.log("[LOCAL_API] " + message); } catch (Throwable ignored) {}
    }

    private static void recordReceived() {
        receivedRequests.incrementAndGet();
        lastRequestAt = System.currentTimeMillis();
        writeRuntimeState();
    }

    private static void recordSuccess(String route) {
        successfulRequests.incrementAndGet();
        lastSuccessAt = System.currentTimeMillis();
        if (route != null) lastRoute = route;
        writeRuntimeState();
    }

    private static void recordFailure(String route, String reason) {
        failedRequests.incrementAndGet();
        lastFailureAt = System.currentTimeMillis();
        if (route != null) lastRoute = route;
        String normalized = reason == null ? "unknown" : reason.replace('\n', ' ').trim();
        if (normalized.length() > 240) normalized = normalized.substring(0, 240);
        lastFailure = normalized;
        AtomicLong count = failureReasons.get(normalized);
        if (count == null) {
            AtomicLong fresh = new AtomicLong();
            AtomicLong raced = failureReasons.putIfAbsent(normalized, fresh);
            count = raced == null ? fresh : raced;
        }
        count.incrementAndGet();
        writeRuntimeState();
    }

    private static void recordCancelled(String route, String reason) {
        cancelledRequests.incrementAndGet();
        if (route != null) lastRoute = route;
        apiLog("REQUEST_CANCELLED route=" + route + " reason=" + reason);
        writeRuntimeState();
    }

    private static synchronized void writeRuntimeState() {
        try {
            JSONObject failures = new JSONObject();
            for (Map.Entry<String, AtomicLong> entry : failureReasons.entrySet()) {
                failures.put(entry.getKey(), entry.getValue().get());
            }
            Backend b = backend;
            ThreadPoolExecutor pool = workers;
            JSONObject state = new JSONObject()
                    .put("listening", isRunning())
                    .put("endpoint", endpoint())
                    .put("protocol", protocolMode())
                    .put("api_key_configured", apiKey != null)
                    .put("backend_ready", b != null && b.isReady())
                    .put("backend_status", b == null ? "not installed" : b.readinessDetail())
                    .put("received", receivedRequests.get())
                    .put("successful", successfulRequests.get())
                    .put("failed", failedRequests.get())
                    .put("cancelled", cancelledRequests.get())
                    .put("tool_calls_emitted", emittedToolCalls.get())
                    .put("response_states", RESPONSE_STATES.size())
                    .put("worker_active", pool == null ? 0 : pool.getActiveCount())
                    .put("worker_queued", pool == null ? 0 : pool.getQueue().size())
                    .put("last_route", lastRoute)
                    .put("last_failure", lastFailure)
                    .put("last_request_at_ms", lastRequestAt)
                    .put("last_success_at_ms", lastSuccessAt)
                    .put("last_failure_at_ms", lastFailureAt)
                    .put("failure_reasons", failures);
            writePath(STATUS_FILE, state.toString(2) + "\n", false);
        } catch (Throwable ignored) {}
    }

    private static void writePath(String path, String value, boolean append) {
        try {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter writer = new FileWriter(file, append);
            writer.write(value);
            writer.close();
        } catch (Throwable ignored) {}
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        Throwable value = t;
        if (t instanceof java.lang.reflect.InvocationTargetException
                && ((java.lang.reflect.InvocationTargetException) t).getCause() != null) {
            value = ((java.lang.reflect.InvocationTargetException) t).getCause();
        }
        String message = value.getMessage();
        String raw = value.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return raw.length() > 500 ? raw.substring(0, 500) : raw;
    }

    private static void closeQuietly(ServerSocket server) {
        if (server != null) try { server.close(); } catch (Throwable ignored) {}
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) try { socket.close(); } catch (Throwable ignored) {}
    }
}
