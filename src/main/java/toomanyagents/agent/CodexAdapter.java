package toomanyagents.agent;


import com.google.gson.*;
import toomanyagents.agent.model.SharedModel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Local Codex app-server stdio transport, based on the installed CLI's v2 schemas. */
public final class CodexAdapter implements AgentHarness {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final Path executable;
    private final String generation = UUID.randomUUID().toString();
    private final ToolHandler toolHandler;
    private final Consumer<Event> listener;
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("too_many_agents-codex-write").factory());
    private final ExecutorService events = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("too_many_agents-codex-events").factory());
    private final AtomicLong ids = new AtomicLong();
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private record Request(JsonElement id, String thread, String turn, String method, JsonObject params, Map<String, JsonObject> choices) {}
    private final Map<String, Request> serverRequests = new ConcurrentHashMap<>();
    private final Map<String, String> conversations = new ConcurrentHashMap<>();
    private final Map<String, String> submissions = new ConcurrentHashMap<>();
    private final Set<String> nativeChildren = ConcurrentHashMap.newKeySet();
    private final Set<String> childSpawns = ConcurrentHashMap.newKeySet();
    private final Set<String> seenTurns = ConcurrentHashMap.newKeySet();
    private final CodexProtocol protocol = new CodexProtocol();
    private final Map<String, Options> execution = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> settlements = new ConcurrentHashMap<>();
    private final Map<String, String> activeTurns = new ConcurrentHashMap<>();
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile boolean closed;
    private CompletableFuture<List<Model>> connection;

    public CodexAdapter(Path executable, ToolHandler toolHandler, Consumer<Event> listener) {
        this.executable = Objects.requireNonNull(executable).toAbsolutePath();
        this.toolHandler = Objects.requireNonNull(toolHandler);
        this.listener = Objects.requireNonNull(listener);
    }

    public static Path defaultExecutable() {
        return toomanyagents.ProviderInstallations.get().executable("codex");
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : new JsonObject();
    }

    @Override public CompletableFuture<JsonObject> usage() {
        return connect().thenCompose(unused -> request("account/rateLimits/read", object())).thenApply(result -> {
            var windows = new JsonArray();
            JsonObject buckets = obj(result, "rateLimitsByLimitId");
            if (buckets.isEmpty()) buckets.add("codex", obj(result, "rateLimits"));
            for (var entry : buckets.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject bucket = entry.getValue().getAsJsonObject();
                String name = string(bucket, "limitName");
                String prefix = entry.getKey().equals("codex") ? "" : (name.isBlank() ? entry.getKey() : name) + " - ";
                for (String key : List.of("primary", "secondary")) {
                    JsonObject window = obj(bucket, key);
                    if (window.isEmpty()) continue;
                    long minutes = window.has("windowDurationMins") && !window.get("windowDurationMins").isJsonNull()
                        ? window.get("windowDurationMins").getAsLong() : 0;
                    String label = minutes <= 0 ? (key.equals("primary") ? "Session" : "Weekly")
                        : minutes % 1440 == 0 ? minutes / 1440 + " days" : minutes % 60 == 0 ? minutes / 60 + " hours" : minutes + " min";
                    var row = object("label", prefix + label, "usedPercent", window.get("usedPercent"));
                    if (window.has("resetsAt") && !window.get("resetsAt").isJsonNull()) row.addProperty("resetsAtMs", window.get("resetsAt").getAsLong() * 1000);
                    windows.add(row);
                }
                JsonObject spend = obj(bucket, "individualLimit");
                if (spend.has("remainingPercent") && !spend.get("remainingPercent").isJsonNull()) {
                    var row = object("label", prefix + "Spend limit", "usedPercent", 100 - spend.get("remainingPercent").getAsDouble());
                    if (spend.has("resetsAt") && !spend.get("resetsAt").isJsonNull()) row.addProperty("resetsAtMs", spend.get("resetsAt").getAsLong() * 1000);
                    windows.add(row);
                }
            }
            return object("windows", windows, "message", windows.isEmpty() ? "No usage limits reported" : "");
        });
    }

    @Override public synchronized CompletableFuture<List<Model>> connect() {
        if (connection != null) return connection;
        if (closed) return CompletableFuture.failedFuture(new IOException("Codex connection is closed."));
        connection = CompletableFuture.runAsync(() -> {
            try {
                Process child = new ProcessBuilder(executable.toString(), "app-server", "--stdio").start();
                synchronized (this) {
                    if (closed) { child.destroy(); throw new IOException("Codex connection is closed."); }
                    process = child;
                    stdin = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
                }
                io.execute(() -> readOutput(child));
                // Drain stderr without exposing account details or arbitrary process output in the game.
                io.execute(() -> {
                    try (InputStream stream = child.getErrorStream()) { stream.transferTo(OutputStream.nullOutputStream()); }
                    catch (IOException ignored) {}
                });
            } catch (IOException e) {
                throw new CompletionException(new IOException("Could not start Codex at " + executable + ". Install Codex or set TOO_MANY_AGENTS_CODEX. " + e.getMessage(), e));
            }
        }, io).thenCompose(ignored -> request("initialize", object(
                "clientInfo", object("name", "too_many_agents", "title", "Too Many Agents for Minecraft", "version", "0.1.0"),
                "capabilities", object("experimentalApi", true))))
            .thenCompose(ignored -> notifyServer("initialized", new JsonObject()))
            .thenCompose(ignored -> request("account/read", object("refreshToken", false)))
            .thenCompose(account -> {
                if (account.has("requiresOpenaiAuth") && account.get("requiresOpenaiAuth").getAsBoolean()
                        && (!account.has("account") || account.get("account").isJsonNull())) {
                    return CompletableFuture.failedFuture(new RequestFailure("Codex is not signed in. Run codex login, then reconnect the agent.", "authRequired", true, new JsonObject()));
                }
                return listModels(null, new ArrayList<>());
            }).thenApply(models -> {

                return models;
            });
        connection.whenComplete((result, failure) -> {
            if (failure != null) {
                emit(new Event("", "", "connection/error", message(failure), object("category", "connection-failed")));
                close();
            }
        });
        return connection;
    }

    private CompletableFuture<List<Model>> listModels(String cursor, List<Model> models) {
        JsonObject params = object("includeHidden", false);
        if (cursor != null) params.addProperty("cursor", cursor);
        return request("model/list", params).thenCompose(response -> {
            for (JsonElement element : response.getAsJsonArray("data")) {
                try {
                JsonObject model = element.getAsJsonObject();
                List<String> efforts = new ArrayList<>();
                for (JsonElement option : model.getAsJsonArray("supportedReasoningEfforts"))
                    efforts.add(string(option.getAsJsonObject(), "reasoningEffort"));
                models.add(new Model(string(model, "model"), string(model, "displayName"), efforts,
                    string(model, "defaultReasoningEffort"), serviceTiers(model), model, true));
                } catch (RuntimeException malformed) {
                    for (String thread : conversations.values()) delta(thread,object("kind","provider.warning","category","general","summary","Skipped an invalid model catalog entry."));
                }
            }
            String next = string(response, "nextCursor");
            return next.isEmpty() ? CompletableFuture.completedFuture(List.copyOf(models)) : listModels(next, models);
        });
    }

    private List<ServiceTier> serviceTiers(JsonObject model) {
        Map<String, ServiceTier> tiers = new LinkedHashMap<>();
        tiers.put("default", new ServiceTier("default", "Standard", "Normal usage"));
        if (model.has("serviceTiers")) {
            if (model.get("serviceTiers").isJsonArray()) {
                for (JsonElement element : model.getAsJsonArray("serviceTiers")) {
                    JsonObject tier = element.getAsJsonObject();
                    String id = string(tier, "id");
                    if (!id.isBlank()) tiers.putIfAbsent(id, new ServiceTier(id, string(tier, "name"), string(tier, "description")));
                }
            }
        } else if (model.has("additionalSpeedTiers") && model.get("additionalSpeedTiers").isJsonArray()) {
            for (JsonElement element : model.getAsJsonArray("additionalSpeedTiers")) {
                String tier = element.getAsString();
                if (tier.isBlank()) continue;
                String id = serviceTier(tier);
                tiers.putIfAbsent(id, new ServiceTier(id, tier.equals("fast") ? "Fast" : tier, ""));
            }
        }
        return List.copyOf(tiers.values());
    }

    @Override public String providerId() { return "codex"; }
    @Override public JsonObject description() {
        JsonObject result = object("id", providerId(), "pluginId", "provider-codex", "name", "Codex", "displayName", "Codex", "family", "openai", "logoUrl", null,
            "available", Files.isExecutable(executable), "maintenance",object("health",true,"usage",true,"installation",false),
            "composerActions",List.of(),"completedTurnDisplay","flat",
            "protocolVersion",2,"grammarVersion",3,
            "extensionKinds",object("provider-codex/goal",object("item",false,"state",true)),
            "extensionSchemas",object("provider-codex/goal",object("state",SharedModel.schema("codexGoalStateSchema"))),
            "capabilities", object("supportsThreadArchive",true,"supportsThreadRename",false,"supportsServiceTier",true,
                "supportsNativeUserQuestion",true,"supportsFork",false,"supportsSessionRewind",false,
                "permissionModes",List.of("accept-edits","auto","full"),"modelCatalogScope","host",
                "sessionRestore", true,"threadArchive", true, "threadRename", false, "fork", "none", "steerMode", "inject", "nativeQuestions", true),
            "permissionFields", List.of(AgentPermissions.field()));
        return result;
    }
    @Override public JsonObject normalizePermissions(JsonObject settings) { return AgentPermissions.normalize(settings); }
    @Override public String permissionMode(Options options) { return string(normalizePermissions(options.permissions()), "permissionMode"); }

    private JsonObject nativePermissions(Options options) {
        String mode = permissionMode(options);
        return object("sandbox", mode.equals("full") ? "danger-full-access" : "workspace-write",
            "approvalPolicy", mode.equals("full") ? "never" : "on-request",
            "approvalsReviewer", mode.equals("auto") ? "auto_review" : "user");
    }
    private JsonObject sandboxPolicy(Options options) {
        return permissionMode(options).equals("full") ? object("type", "dangerFullAccess")
            : object("type", "workspaceWrite", "writableRoots", options.writableDirectories().stream().map(Path::toString).toList(),
                "networkAccess", true, "excludeTmpdirEnvVar", false, "excludeSlashTmp", false);
    }

    @Override public CompletableFuture<Session> start(String conversationId, Options options) {
        return connect().thenCompose(ignored -> {
            JsonObject params = threadParams(options);
            params.addProperty("allowProviderModelFallback", false);
            params.addProperty("ephemeral", false);
            params.addProperty("experimentalRawEvents", true);
            return request("thread/start", params).thenCompose(response -> {
                Session created = session(conversationId, response);
                initializeSession(created, response);
                // Materialize empty native conversations without spending a model turn.
                // Work-only sessions use an empty item so this adds no startup context.
                JsonArray content = new JsonArray();
                content.add(object("type", "input_text", "text", options.instructions().isBlank() ? "" : "Too Many Agents initialized this Minecraft agent conversation."));
                return request("thread/inject_items", object("threadId", created.providerSessionId(), "items",
                    List.of(object("type", "message", "role", "developer", "content", content)))).thenApply(done -> created);
            });
        });
    }
    @Override public CompletableFuture<Session> resume(String conversationId, String providerSessionId, Options options) {
        conversations.put(providerSessionId, conversationId);
        return connect().thenCompose(ignored -> {
            JsonObject params = threadParams(options);
            params.addProperty("threadId", providerSessionId);
            return request("thread/resume", params).exceptionallyCompose(failure -> {
                Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
                if (!(cause instanceof RequestFailure rejected) || !rejected.rejected || !rejected.recovery.equals("sessionArchived"))
                    return CompletableFuture.failedFuture(failure);
                return request("thread/unarchive",object("threadId",providerSessionId)).thenCompose(unused -> request("thread/resume",params));
            }).thenApply(response -> {
                Session resumed = session(conversationId, response);
                if (!resumed.providerSessionId().equals(providerSessionId)) throw new RequestFailure(
                    "Codex resumed a different native conversation; no input was sent.","restartRecommended",true,new JsonObject());
                initializeSession(resumed, response);
                return resumed;
            });
        });
    }
    private void initializeSession(Session session, JsonObject response) {
        conversations.put(session.providerSessionId(), session.threadId());
        delta(session.threadId(), object("kind", "thread.identity", "providerThreadId", session.providerSessionId()));
        delta(session.threadId(), object("kind", "session.reset"));
        String title = string(response.getAsJsonObject("thread"), "name");
        if (!title.isBlank()) delta(session.threadId(), object("kind", "thread.name", "name", title));
    }
    private JsonObject threadParams(Options options) {
        JsonObject permissions = nativePermissions(options);
        JsonObject params = object("cwd", options.directory().toAbsolutePath().normalize().toString(),
            "runtimeWorkspaceRoots", options.directories().stream().map(Path::toString).toList(),
            "serviceTier", serviceTier(options.serviceTier()), "sandbox", permissions.get("sandbox"),
            "approvalPolicy", permissions.get("approvalPolicy"), "approvalsReviewer", permissions.get("approvalsReviewer"));
        if (options.model() != null && !options.model().isBlank()) params.addProperty("model", options.model());
        JsonObject config = object("sandbox_workspace_write.network_access", true,
            "sandbox_workspace_write.writable_roots", options.writableDirectories().stream().map(Path::toString).toList(),
            "features.default_mode_request_user_input", true);
        // Resume can retain the previous thread overrides; write both sides of the toggle.
        config.addProperty("features.multi_agent", options.nativeSubagentsEnabled());
        // V1 model metadata can enable native tools independently of the feature flag.
        config.addProperty("agents.max_depth", options.nativeSubagentsEnabled() ? 1 : 0);
        config.addProperty("features.multi_agent_v2.max_concurrent_threads_per_session", options.nativeSubagentsEnabled() ? 6 : 1);
        if (options.effort() != null && !options.effort().isBlank()) config.addProperty("model_reasoning_effort", options.effort());
        params.add("config", config);
        if (!options.instructions().isBlank()) params.addProperty("developerInstructions", options.instructions());
        JsonArray definitions = new JsonArray();
        for (ToolDefinition tool : options.tools()) definitions.add(object("type", "function", "name", tool.name(),
            "description", tool.description(), "inputSchema", tool.inputSchema()));
        params.add("dynamicTools", definitions);
        return params;
    }
    private Session session(String conversationId, JsonObject response) {
        return new Session(conversationId, string(response.getAsJsonObject("thread"), "id"), generation, Path.of(string(response, "cwd")),
            string(response, "model"), string(response, "reasoningEffort"), serviceTier(string(response, "serviceTier")), true);
    }
    @Override public CompletableFuture<String> send(Session session, String requestId, Input input, Options options) {
        JsonObject permissions = nativePermissions(options);
        JsonObject params = object("threadId", session.providerSessionId(), "input", nativeInput(input),
            "serviceTier", serviceTier(options.serviceTier()), "approvalPolicy", permissions.get("approvalPolicy"),
            "approvalsReviewer", permissions.get("approvalsReviewer"), "sandboxPolicy", sandboxPolicy(options));
        if (!options.model().isBlank()) params.addProperty("model", options.model());
        if (!options.effort().isBlank()) params.addProperty("effort", options.effort());
        protocol.expectUserTurn(session.providerSessionId());
        execution.put(session.providerSessionId(), options);
        submissions.put(session.providerSessionId(), requestId);
        return request("turn/start", params).thenApply(response -> {
            String turn = string(response.getAsJsonObject("turn"), "id");
            openTurn(session.providerSessionId(), turn);
            return turn;
        }).whenComplete((turn, failure) -> { if (failure != null) { submissions.remove(session.providerSessionId(), requestId); protocol.cancelExpectedUserTurn(session.providerSessionId()); } });
    }
    private JsonArray nativeInput(Input input) {
        JsonArray nativeParts = new JsonArray();
        for (JsonElement value : input.content()) {
            JsonObject part = value.getAsJsonObject();
            switch(string(part,"type")) {
                case "text" -> nativeParts.add(object("type","text","text",part.get("text"),"text_elements",List.of()));
                case "image" -> nativeParts.add(object("type","image","url",part.get("url")));
                case "localImage" -> nativeParts.add(object("type","localImage","path",part.get("path")));
                case "localFile" -> nativeParts.add(object("type","text","text","[Attached file: " + string(part,"path") + "]","text_elements",List.of()));
                default -> throw new RequestFailure("Unsupported prompt input: " + string(part,"type"),"",true,new JsonObject());
            }
        }
        return nativeParts;
    }

    private synchronized void openTurn(String nativeThread, String turn) {
        if (turn.isBlank() || !seenTurns.add(nativeThread + ":" + turn)) return;
        String thread = conversations.get(nativeThread);
        if (thread == null) return;
        String request = submissions.remove(nativeThread);
        activeTurns.put(nativeThread, turn);
        settlements.put(nativeThread, new CompletableFuture<>());
        for (JsonObject value : protocol.translate("turn/started",object("threadId",nativeThread,"turn",object("id",turn)))) delta(thread,value);
        if (request != null) delta(thread, object("kind", "input.accepted", "clientRequestId", request, "providerTurnId", turn));
    }
    @Override public CompletableFuture<Void> steer(Session session, String requestId, String expectedTurnId, Input input, Options options) {
        Options current = execution.get(session.providerSessionId());
        if (current != null) options = new Options(options.directory(), options.additionalDirectories(), options.instructions(), options.model(), options.effort(),
            options.permissions(), options.tools(), options.serviceTier(), current.nativeSubagentsEnabled());
        if (current == null || !current.equals(options)) return CompletableFuture.failedFuture(
            new RequestFailure("Codex cannot change execution settings while steering an active turn. Queue the message with those settings.", "staleTurn", true, new JsonObject()));
        return request("turn/steer", object("threadId", session.providerSessionId(), "expectedTurnId", expectedTurnId,
            "input", nativeInput(input))).thenAccept(ignored ->
                delta(session.threadId(), object("kind", "input.accepted", "clientRequestId", requestId, "providerTurnId", expectedTurnId)));
    }
    @Override public CompletableFuture<Void> interrupt(Session session) {
        String turn = activeTurns.get(session.providerSessionId());
        if (turn == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> settled = settlements.get(session.providerSessionId());
        return request("turn/interrupt", object("threadId", session.providerSessionId(), "turnId", turn))
            .thenCompose(ignored -> settled == null ? CompletableFuture.completedFuture(null) : settled.copy().orTimeout(10, TimeUnit.SECONDS));
    }
    @Override public CompletableFuture<Void> release(Session session) {
        if (activeTurns.containsKey(session.providerSessionId())) return CompletableFuture.failedFuture(new IllegalStateException("Stop the turn before releasing its session."));
        // Let app-server unload the conversation before its process exits, releasing its writer.
        var unloaded = conversations.containsKey(session.providerSessionId())
            ? request("thread/unsubscribe", object("threadId", session.providerSessionId()))
            : CompletableFuture.completedFuture(new JsonObject());
        return unloaded.thenCompose(unused -> {
            for (JsonObject value : protocol.settle(session.providerSessionId(),"interrupted")) delta(session.threadId(),value);
            conversations.remove(session.providerSessionId(), session.threadId());
            protocol.clearThread(session.providerSessionId());
            serverRequests.entrySet().removeIf(e -> e.getValue().thread().equals(session.threadId()));
            delta(session.threadId(), object("kind", "session.ended"));
            CompletableFuture<Void> delivered = new CompletableFuture<>();
            events.execute(() -> delivered.complete(null));
            return delivered;
        });
    }
    @Override public CompletableFuture<Void> archive(Session session) {
        return connect().thenCompose(ignored -> request("thread/archive", object("threadId", session.providerSessionId())))
            .thenCompose(ignored -> release(session));
    }
    @Override public CompletableFuture<Void> respond(String requestId, JsonObject answer) {
        Request request = serverRequests.get(requestId);
        if (request == null) return CompletableFuture.failedFuture(new IllegalArgumentException("This request is no longer pending."));
        JsonObject response;
        try { response = protocol.response(request.method(), request.params(), answer); }
        catch (IllegalArgumentException invalid) { return CompletableFuture.failedFuture(invalid); }
        return write(object("id", request.id(), "result", response)).thenRun(() -> {
            serverRequests.remove(requestId, request);
            emit(new Event(request.thread(), request.turn(), "interaction/resolved", "", object("id", requestId, "answer", answer)));
        });
    }

    private CompletableFuture<JsonObject> request(String method, JsonObject params) {
        String id = "too_many_agents-" + ids.incrementAndGet();
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new IOException("Codex connection is closed."));
        pending.put(id, result);
        write(object("id", id, "method", method, "params", params)).whenComplete((ignored, failure) -> {
            if (failure != null) result.completeExceptionally(failure);
        });
        CompletableFuture.delayedExecutor(90, TimeUnit.SECONDS).execute(() -> result.completeExceptionally(
                new RequestFailure("Codex did not acknowledge " + method + " within 90 seconds. Its outcome may be unknown; the request was not retried.", "restartRecommended", false, object("method",method))));
        result.whenComplete((ignored, failure) -> pending.remove(id));
        return result;
    }

    private CompletableFuture<Void> notifyServer(String method, JsonObject params) {
        return write(object("method", method, "params", params));
    }

    private CompletableFuture<Void> write(JsonObject message) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Codex connection is closed."));
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    BufferedWriter stream = stdin;
                    if (stream == null || closed) throw new IOException("Codex is not connected.");
                    stream.write(JSON.toJson(message));
                    stream.newLine();
                    stream.flush();
                } catch (IOException e) { throw new CompletionException(e); }
            }, writer);
        } catch (RejectedExecutionException e) { return CompletableFuture.failedFuture(e); }
    }

    private void readOutput(Process child) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    try { receive(JsonParser.parseString(line).getAsJsonObject()); }
                    catch (JsonParseException | IllegalStateException malformed) {
                        for (String thread : conversations.values()) delta(thread, object("kind", "provider.warning", "summary", "Codex sent an invalid protocol entry.", "category", "general"));
                    }
                }
            }
            if (!closed) fail(new IOException("Codex app-server disconnected. Reconnect before sending more messages."));
        } catch (Exception e) {
            if (!closed) fail(new IOException("Codex connection failed: " + message(e), e));
        }
    }

    private void receive(JsonObject packet) {
        if (!packet.has("method")) {
            CompletableFuture<JsonObject> request = pending.remove(string(packet, "id"));
            if (request != null) {
                if (packet.has("error")) request.completeExceptionally(requestFailure(packet.getAsJsonObject("error")));
                else request.complete(packet.has("result") && packet.get("result").isJsonObject() ? packet.getAsJsonObject("result") : new JsonObject());
            }
            return;
        }
        String method = string(packet, "method");
        JsonObject params = packet.has("params") && packet.get("params").isJsonObject() ? packet.getAsJsonObject("params") : new JsonObject();
        String nativeThread = string(params, "threadId"), thread = conversations.get(protocol.rootThread(nativeThread));
        String turn = string(params, "turnId");
        if (params.has("turn") && params.get("turn").isJsonObject()) turn = string(params.getAsJsonObject("turn"), "id");
        trackNativeChildren(method, params);
        if (packet.has("id")) {
            if (method.equals("item/tool/call")) { handleTool(packet.get("id"), params); return; }
            if (thread == null) { write(object("id", packet.get("id"), "error", object("code", -32601, "message", "No Too Many Agents conversation owns this request."))); return; }
            JsonObject interaction;
            try { interaction = protocol.interaction(method, params); }
            catch (IllegalArgumentException invalid) {
                write(object("id", packet.get("id"), "error", object("code", -32602, "message", invalid.getMessage())));
                delta(thread, object("kind", "provider.warning", "summary", "Codex sent an unsupported interaction: " + invalid.getMessage(), "category", "general"));
                return;
            }
            if (interaction == null) {
                write(object("id", packet.get("id"), "error", object("code", -32601, "message", "Unsupported interaction: " + method)));
                delta(thread, object("kind", "provider.error", "message", "Unsupported Codex interaction: " + method, "threadScoped", true));
                return;
            }
            String token = "request-" + ids.incrementAndGet();
            serverRequests.put(token, new Request(packet.get("id").deepCopy(), thread, turn, method, params.deepCopy(), protocol.decisions(method, params)));
            emit(new Event(thread, turn, "interaction/request", "", object("id", token, "payload", interaction)));
            return;
        }
        if (method.equals("serverRequest/resolved")) {
            for (var entry : serverRequests.entrySet()) if (entry.getValue().id().equals(params.get("requestId"))) {
                var old = entry.getValue(); serverRequests.remove(entry.getKey(), old);
                emit(new Event(old.thread(), old.turn(), "interaction/resolved", "", object("id", entry.getKey())));
            }
            return;
        }
        if (thread == null) {
            if (method.equals("account/rateLimits/updated")) {
                List<JsonObject> deltas = protocol.translate(method, params);
                for (String owned : new HashSet<>(conversations.values())) for (JsonObject value : deltas) delta(owned, value.deepCopy());
            }
            return;
        }
        if (method.equals("turn/started")) {
            if (conversations.containsKey(nativeThread)) openTurn(nativeThread, turn);
            else for (JsonObject value : protocol.translate(method,params)) delta(thread,value);
            return;
        }
        // Native helper conversations keep their own titles.
        if (method.equals("thread/name/updated") && !conversations.containsKey(nativeThread)) return;
        for (JsonObject delta : protocol.translate(method, params)) delta(thread, delta);
        if (method.equals("item/completed")) for (JsonObject question : protocol.asyncQuestions(params))
            emit(new Event(thread, turn, "interaction/request", "", question));
        if (method.equals("turn/completed")) {
            activeTurns.remove(nativeThread, turn);
            CompletableFuture<Void> settled = settlements.remove(nativeThread);
            if (settled != null) events.execute(() -> settled.complete(null));
            final String completedTurn = turn;
            serverRequests.entrySet().removeIf(entry -> entry.getValue().thread().equals(thread) && entry.getValue().turn().equals(completedTurn));
        }
    }
    boolean canReconfigure() {
        return activeTurns.isEmpty() && submissions.isEmpty() && childSpawns.isEmpty() && nativeChildren.isEmpty() && serverRequests.isEmpty()
            && conversations.keySet().stream().noneMatch(protocol::hasWork);
    }
    private void trackNativeChildren(String method, JsonObject params) {
        if (method.equals("thread/closed")) nativeChildren.remove(string(params,"threadId"));
        if (!params.has("item") || !params.get("item").isJsonObject()) return;
        JsonObject item = params.getAsJsonObject("item");
        if (!string(item,"type").equals("collabAgentToolCall")) return;
        String tool = string(item,"tool"), id = string(item,"id");
        if (Set.of("spawnAgent","resumeAgent","sendInput").contains(tool)) {
            if (method.equals("item/started")) childSpawns.add(id);
            if (item.has("receiverThreadIds") && item.get("receiverThreadIds").isJsonArray())
                for (JsonElement child : item.getAsJsonArray("receiverThreadIds")) nativeChildren.add(child.getAsString());
            if (method.equals("item/completed")) childSpawns.remove(id);
        }
        // Keep idle children attached until explicitly closed: rebuilding would kill their process.
        if (tool.equals("closeAgent") && method.equals("item/completed") && string(item,"status").equals("completed")
                && item.has("receiverThreadIds") && item.get("receiverThreadIds").isJsonArray())
            for (JsonElement child : item.getAsJsonArray("receiverThreadIds")) nativeChildren.remove(child.getAsString());
    }
    private void delta(String thread, JsonObject delta) {
        emit(new Event(thread, string(delta, "providerTurnId"), "delta", "", delta));
    }

    private void handleTool(JsonElement id, JsonObject params) {
        String thread = conversations.get(protocol.rootThread(string(params, "threadId")));
        String turn = string(params, "turnId");
        // Preserve turn/started and turn/completed ordering at tool admission.
        events.execute(() -> {
            try {
                String name = string(params, "tool");
                JsonElement args = params.get("arguments");
                if (args == null || !args.isJsonObject()) throw new IllegalArgumentException("Tool arguments must be an object.");
                toolHandler.call(thread, turn, name, args.getAsJsonObject().deepCopy()).whenComplete((result, failure) -> {
                    try {
                        boolean success = failure == null && result != null && !(result.has("ok") && !result.get("ok").getAsBoolean());
                        if (failure != null) replyTool(id, false, message(failure));
                        else replyTool(id, success, result);
                    } catch (Exception invalidResult) { replyTool(id, false, message(invalidResult)); }
                });
            } catch (Exception e) { replyTool(id, false, message(e)); }
        });
    }

    private void replyTool(JsonElement id, boolean success, String text) {
        JsonArray content = new JsonArray();
        content.add(object("type", "inputText", "text", text));
        write(object("id", id, "result", object("success", success, "contentItems", content)));
    }

    private void replyTool(JsonElement id, boolean success, JsonObject result) {
        if (result == null) { replyTool(id, false, "The tool returned no result."); return; }
        JsonObject metadata = result.deepCopy();
        JsonElement image = metadata.remove("imageDataUrl");
        JsonArray content = new JsonArray();
        content.add(object("type", "inputText", "text", JSON.toJson(metadata)));
        if (image != null && success) {
            if (!image.isJsonPrimitive() || !image.getAsJsonPrimitive().isString()
                    || !image.getAsString().startsWith("data:image/png;base64,") || image.getAsString().length() > 8_000_000) {
                throw new IllegalArgumentException("The tool returned an invalid or oversized PNG image.");
            }
            // Installed app-server DynamicToolCallResponse schema: pixels, not a path or JSON string.
            content.add(object("type", "inputImage", "imageUrl", image.getAsString()));
        }
        write(object("id", id, "result", object("success", success, "contentItems", content)));
    }

    private void emit(Event event) {
        try { events.execute(() -> { try { listener.accept(event); } catch (RuntimeException rejected) {
            LOG.warn("Could not handle a normalized Codex event: {}", message(rejected));
            close();
        } }); }
        catch (RejectedExecutionException ignored) {}
    }

    private void fail(Throwable failure) {
        for (var entry : conversations.entrySet()) for (JsonObject value : protocol.settle(entry.getKey(),"failed")) delta(entry.getValue(),value);
        pending.values().forEach(future -> future.completeExceptionally(failure));
        emit(new Event("", "", "connection/error", message(failure), object("category", "stream-disconnected")));
        close();
    }

    CompletableFuture<Void> abort(String reason) {
        for (var entry : conversations.entrySet()) for (JsonObject value : protocol.settle(entry.getKey(),"interrupted")) delta(entry.getValue(),value);
        emit(new Event("","","connection/error",reason,object("kind","restartRecommended","outcome","unknown")));
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        events.execute(() -> delivered.complete(null));
        return delivered.thenCompose(ignored -> terminate());
    }

    CompletableFuture<Void> terminate() {
        close();
        Process child = process;
        return child == null ? CompletableFuture.completedFuture(null) : child.onExit().orTimeout(5,TimeUnit.SECONDS).thenApply(ignored -> null);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.values().forEach(future -> future.completeExceptionally(new IOException("Codex connection is closed.")));
        pending.clear();
        serverRequests.clear();
        activeTurns.clear();
        settlements.values().forEach(future -> future.completeExceptionally(new RequestFailure("Codex disconnected before work settled.","restartRecommended",false,new JsonObject())));
        settlements.clear();
        Process child = process;
        if (child != null) {
            List<ProcessHandle> descendants = child.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            child.destroy();
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly));
            // Do not wait for a native process on Minecraft's render or server thread.
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> { if (child.isAlive()) child.destroyForcibly(); });
        }
        writer.shutdownNow();
        io.shutdown();
        events.shutdown();
    }

    private RequestFailure requestFailure(JsonObject error) {
        JsonObject data = error.has("data") && error.get("data").isJsonObject() ? error.getAsJsonObject("data") : new JsonObject();
        JsonObject recovery = data.has("recovery") && data.get("recovery").isJsonObject() ? data.getAsJsonObject("recovery") : new JsonObject();
        String kind = string(recovery,"kind");
        if (kind.isBlank()) {
            String code = string(data,"codexErrorInfo");
            kind = switch (code) {
                case "unauthorized" -> "authRequired";
                case "usageLimitExceeded" -> "rateLimited";
                case "activeTurnNotSteerable" -> "staleTurn";
                default -> "";
            };
        }
        // Native app-server exposes archived-session rejection as text; classify once at this boundary.
        if (kind.isBlank() && string(error,"message").matches("(?is).*\\b(?:session|thread)\\s+\\S+\\s+is archived\\b.*")) kind = "sessionArchived";
        return new RequestFailure("Codex: " + string(error,"message"),kind,true,error);
    }

    private static String message(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static String serviceTier(String tier) {
        if (tier == null || tier.isBlank()) return "default";
        return tier.equals("fast") ? "priority" : tier;
    }

    private static JsonObject object(Object... entries) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < entries.length; i += 2) object.add((String) entries[i], JSON.toJsonTree(entries[i + 1]));
        return object;
    }
}
