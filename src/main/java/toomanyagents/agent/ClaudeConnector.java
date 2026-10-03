package toomanyagents.agent;

import toomanyagents.agent.model.SharedModel;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns one SDK helper per conversation. Native Claude translation lives in the bundled helper. */
public final class ClaudeConnector implements AgentHarness {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final ToolHandler tools;
    private final Consumer<Event> listener;
    private final Map<String, Connection> sessions = new HashMap<>();
    private final Map<String, Connection> interactions = new HashMap<>();
    private Connection catalog;
    private CompletableFuture<List<Model>> models;
    private long catalogTime;
    private boolean closed;
    private String availability = "unknown", availabilityMessage = "";

    public ClaudeConnector(ToolHandler tools, Consumer<Event> listener) { this.tools = tools; this.listener = listener; }
    @Override public String providerId() { return "claude"; }
    @Override public synchronized JsonObject description() {
        JsonObject result = object("id","claude","pluginId","provider-claude-code","name","Claude Code","displayName","Claude Code",
            "family","anthropic","logoUrl",null,"available",availability.equals("ready"),"protocolVersion",2,"grammarVersion",3,
            "maintenance",object("health",true,"usage",true,"installation",false),"composerActions",List.of(),"completedTurnDisplay","flat",
            "availability",object("state",availability,"message",availabilityMessage),"extensionKinds",object(),"extensionSchemas",object(),
            "capabilities",object("supportsThreadArchive",false,"supportsThreadRename",false,"supportsServiceTier",true,
                "supportsNativeUserQuestion",true,"supportsFork",false,"supportsSessionRewind",false,
                "permissionModes",List.of("accept-edits","auto","full"),"modelCatalogScope","host",
                "grammarVersions",List.of(3,3),"approvalEnforcedBy","provider","sessionRestore",true,"threadArchive",false,"threadRename",false,"fork","none","steerMode","inject","nativeQuestions",true),
            "permissionFields",List.of(AgentPermissions.field()));
        SharedModel.validate("providerInfoSchema",result);
        SharedModel.validate("bridgeCapabilitiesSchema",result.getAsJsonObject("capabilities"));
        return result;
    }
    @Override public JsonObject normalizePermissions(JsonObject settings) { return AgentPermissions.normalize(settings); }
    @Override public synchronized CompletableFuture<JsonObject> usage() {
        var connected = connect();
        var owner = catalog;
        return connected.thenCompose(unused -> owner.request("usage", object()));
    }
    @Override public String permissionMode(Options options) { return string(normalizePermissions(options.permissions()), "permissionMode"); }
    @Override public synchronized CompletableFuture<List<Model>> connect() {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Claude connector closed"));
        if (models != null && (!models.isDone() || !models.isCompletedExceptionally() && System.nanoTime()-catalogTime < 60_000_000_000L)) return models;
        if (catalog != null) catalog.terminate();
        Connection owner = catalog = new Connection("");
        availability = "connecting"; catalogTime = System.nanoTime();
        models = owner.launch().thenCompose(unused -> owner.request("catalog",object())).thenApply(response -> {
            List<Model> result = new ArrayList<>();
            for (JsonElement entry : response.getAsJsonArray("models")) {
                try {
                JsonObject value = entry.getAsJsonObject();
                JsonObject descriptor = value.has("descriptor") ? value.getAsJsonObject("descriptor") : value;
                SharedModel.validate("availableModelSchema",descriptor);
                List<String> efforts = new ArrayList<>();
                for (JsonElement effort : descriptor.getAsJsonArray("supportedReasoningEfforts")) efforts.add(string(effort.getAsJsonObject(),"reasoningEffort"));
                List<ServiceTier> tiers = new ArrayList<>();
                if (value.has("serviceTiers")) for (JsonElement item : value.getAsJsonArray("serviceTiers")) {
                    JsonObject tier = item.getAsJsonObject(); tiers.add(new ServiceTier(string(tier,"id"),string(tier,"name"),string(tier,"description")));
                }
                if (tiers.isEmpty()) tiers.add(new ServiceTier("default","Standard","Normal usage"));
                Boolean supportsAutoMode = value.has("supportsAutoMode") && !value.get("supportsAutoMode").isJsonNull() ? value.get("supportsAutoMode").getAsBoolean() : null;
                result.add(new Model(string(descriptor,"model"),string(descriptor,"displayName"),efforts,string(descriptor,"defaultReasoningEffort"),tiers,descriptor,supportsAutoMode));
                } catch (RuntimeException malformed) { LOG.warn("Skipped an invalid Claude model catalog entry: {}", malformed.getMessage()); }
            }
            if (result.isEmpty()) throw new IllegalStateException("Claude reported no models");
            return List.copyOf(result);
        });
        models.whenComplete((result,error) -> { synchronized(this) {
            if (catalog != owner) return;
            Throwable cause = unwrap(error);
            availability = cause == null ? "ready" : cause instanceof RequestFailure failure && failure.recovery.equals("authRequired") ? "authRequired" : "unavailable";
            availabilityMessage = cause == null ? "" : cause.getMessage();
        }});
        return models;
    }
    private synchronized Connection create(String id) {
        if (closed) throw new IllegalStateException("Claude connector closed");
        if (sessions.containsKey(id)) throw new IllegalStateException("Conversation already connected");
        Connection result = new Connection(id); sessions.put(id,result); return result;
    }
    private synchronized Connection require(Session session) {
        Connection result = sessions.get(session.threadId());
        if (result == null || !result.connectionId.equals(session.connectionId()) || result.stopped) throw new IllegalStateException("Conversation connection has changed");
        return result;
    }
    private JsonObject options(Options options) {
        JsonArray definitions = new JsonArray();
        for (ToolDefinition tool : options.tools()) definitions.add(object("name",tool.name(),"description",tool.description(),"inputSchema",tool.inputSchema()));
        return object("cwd",options.directory().toString(),"additionalDirectories",options.additionalDirectories().stream().map(Path::toString).toList(),
            "writableDirectories",options.writableDirectories().stream().map(Path::toString).toList(),"instructions",options.instructions(),"model",options.model(),
            "effort",options.effort(),"serviceTier",options.serviceTier(),"permissions",normalizePermissions(options.permissions()),"tools",definitions,"nativeSubagentsEnabled",options.nativeSubagentsEnabled());
    }
    private CompletableFuture<Session> open(String id,String nativeId,Options options) {
        JsonObject params = options(options); params.addProperty("threadId",id);
        Connection owner = create(id); owner.instructions = options.instructions(); owner.definitions = options.tools();
        owner.workingDirectory = options.directory(); owner.additionalDirectories = options.additionalDirectories();
        if (nativeId != null) params.addProperty("providerSessionId",nativeId);
        return owner.launch().thenCompose(unused -> owner.request(nativeId == null ? "start" : "resume",params)).thenApply(result ->
            new Session(id,string(result,"providerSessionId"),owner.connectionId,options.directory(),options.model(),options.effort(),options.serviceTier(),result.get("restorable").getAsBoolean())
        ).whenComplete((result,error) -> { if (error != null) discard(owner); });
    }
    @Override public CompletableFuture<Session> start(String id,Options options) { return open(id,null,options); }
    @Override public CompletableFuture<Session> resume(String id,String nativeId,Options options) { return open(id,nativeId,options); }
    @Override public CompletableFuture<String> send(Session session,String requestId,Input input,Options options) {
        Connection owner = require(session);
        if (!Objects.equals(owner.instructions,options.instructions()) || !owner.definitions.equals(options.tools()) || !owner.workingDirectory.equals(options.directory()) || !owner.additionalDirectories.equals(options.additionalDirectories()))
            return CompletableFuture.failedFuture(new RequestFailure("Claude instructions, folders, or tools changed. Release and resume the session first.","restartRecommended",true,object()));
        JsonObject params = options(options); params.addProperty("threadId",session.threadId()); params.addProperty("requestId",requestId); params.add("input",input.content());
        return owner.request("send",params).thenApply(result -> string(result,"turnId"));
    }
    @Override public CompletableFuture<Void> steer(Session session,String requestId,String turn,Input input,Options options) {
        Connection owner = require(session);
        JsonObject params = object("threadId",session.threadId(),"requestId",requestId,"expectedTurnId",turn,"input",input.content());
        return owner.request("steer",params).thenAccept(unused -> {});
    }
    @Override public CompletableFuture<Boolean> updatePermissions(Session session,JsonObject from,JsonObject to) {
        // The sandbox is fixed when the session opens, so Full Access changes wait for the next turn.
        if (string(normalizePermissions(from),"permissionMode").equals("full") || string(normalizePermissions(to),"permissionMode").equals("full"))
            return CompletableFuture.completedFuture(false);
        return require(session).request("permissions",object("threadId",session.threadId(),"permissions",normalizePermissions(to))).orTimeout(10,TimeUnit.SECONDS).thenApply(unused -> true);
    }
    @Override public CompletableFuture<Void> interrupt(Session session) {
        Connection owner = require(session);
        return owner.request("interrupt",object("threadId",session.threadId())).orTimeout(15,TimeUnit.SECONDS).thenAccept(unused -> {})
            .exceptionallyCompose(error -> {
                owner.fail("Claude did not settle after Stop; its owned processes were terminated. Action outcomes may be unknown.");
                return owner.terminate();
            });
    }
    @Override public CompletableFuture<Void> release(Session session) {
        Connection owner;
        synchronized(this) { owner = sessions.get(session.threadId()); if (owner == null) return CompletableFuture.completedFuture(null); owner = require(session); }
        Connection target = owner;
        return target.request("release",object("threadId",session.threadId())).orTimeout(10,TimeUnit.SECONDS)
            .handle((result,error) -> { if (error != null) target.fail("Claude release did not settle; its owned processes were terminated."); return null; })
            .thenCompose(unused -> discard(target));
    }
    @Override public CompletableFuture<Void> archive(Session session) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Claude does not support native session archival")); }
    @Override public CompletableFuture<Void> respond(String id,JsonObject answer) {
        Connection owner; synchronized(this) { owner = interactions.get(id); }
        if (owner == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Request is no longer pending"));
        return owner.request("respond",object("requestId",id.substring(owner.thread.length()+1),"answer",answer)).thenAccept(unused -> {});
    }
    private CompletableFuture<Void> discard(Connection owner) {
        synchronized(this) { sessions.remove(owner.thread,owner); interactions.values().removeIf(value -> value == owner); }
        return owner.terminate();
    }
    private void deliver(Connection owner,Event event) {
        synchronized(this) { if (sessions.get(owner.thread) != owner || owner.stopped) return; }
        listener.accept(event);
    }
    @Override public void close() {
        List<Connection> owned;
        synchronized(this) { if (closed) return; closed = true; owned = new ArrayList<>(sessions.values()); if (catalog != null) owned.add(catalog); sessions.clear(); interactions.clear(); }
        owned.forEach(Connection::terminate);
    }

    private final class Connection {
        final String thread, connectionId = UUID.randomUUID().toString();
        final Map<String,CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        final Set<ProcessHandle> ownedChildren = ConcurrentHashMap.newKeySet();
        final AtomicLong ids = new AtomicLong();
        final ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("too_many_agents-claude-writer").factory());
        volatile boolean stopped;
        volatile RequestFailure terminalFailure = new RequestFailure("Claude connection closed; outstanding action outcomes may be unknown.","restartRecommended",false,object());
        boolean failing;
        CompletableFuture<Void> termination;
        final Map<String, JsonObject> turns = new LinkedHashMap<>();
        final Map<ItemKey, JsonObject> items = new LinkedHashMap<>();
        Process process;
        BufferedWriter input;
        Path directory, workingDirectory;
        List<Path> additionalDirectories;
        String instructions;
        List<ToolDefinition> definitions;
        Connection(String thread) { this.thread = thread; }
        CompletableFuture<Void> launch() {
            return CompletableFuture.runAsync(() -> {
                try {
                    Path node = executable("TOO_MANY_AGENTS_NODE","node");
                    Path claude = toomanyagents.ProviderInstallations.get().executable("claude");
                    synchronized(this) {
                        if (stopped) throw new IOException("Claude connection closed");
                        directory = Files.createTempDirectory("too-many-agents-claude-");
                        Path helper = directory.resolve("bridge.mjs");
                        try (InputStream source = ClaudeConnector.class.getResourceAsStream("/too_many_agents/claude/bridge.mjs")) {
                            if (source == null) throw new IOException("Bundled Claude helper is missing. Build Too Many Agents before connecting.");
                            Files.copy(source,helper);
                        }
                        ProcessBuilder builder = new ProcessBuilder(node.toString(),helper.toString());
                        builder.environment().put("TOO_MANY_AGENTS_CLAUDE",claude.toString());
                        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                        process = builder.start(); input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
                        Thread.ofVirtual().name("too_many_agents-claude-reader").start(this::read);
                        Thread.ofVirtual().name("too_many_agents-claude-children").start(() -> {
                            while (!stopped && process.isAlive()) {
                                rememberChildren();
                                try { Thread.sleep(100); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                            }
                        });
                    }
                } catch (Exception error) { throw new CompletionException(error); }
            }).thenCompose(unused -> request("hello",object("protocol",2,"grammar",3))).thenAccept(hello -> {
                if (!string(hello,"protocol").equals("2") || !string(hello,"grammar").equals("3")) throw new IllegalStateException("Incompatible Claude helper protocol or grammar");
            }).whenComplete((unused,error) -> { if (error != null) terminate(); });
        }
        CompletableFuture<JsonObject> request(String method,JsonObject params) {
            String id = "host-" + ids.incrementAndGet(); CompletableFuture<JsonObject> result = new CompletableFuture<>();
            pending.put(id,result);
            try { writer.execute(() -> {
                try { write(object("id",id,"method",method,"params",params)); }
                catch (Exception error) {
                    result.completeExceptionally(new RequestFailure("Claude delivery failed; its outcome may be unknown.","restartRecommended",false,object("method",method)));
                    fail("Claude delivery failed; reconnect before sending more work.");
                }
            }); } catch (RejectedExecutionException closedWriter) {
                result.completeExceptionally(new RequestFailure("Claude connection is closed.","restartRecommended",true,object("method",method)));
            }
            CompletableFuture.delayedExecutor(45,TimeUnit.SECONDS).execute(() -> {
                if (result.completeExceptionally(new RequestFailure("Claude did not acknowledge " + method + "; its outcome may be unknown. No action was retried.","restartRecommended",false,object("method",method)))) fail("Claude helper stopped responding; reconnect before sending more work.");
            });
            result.whenComplete((unused,error) -> pending.remove(id)); return result;
        }
        void write(JsonObject packet) throws IOException {
            if (stopped || input == null) throw new IOException("Claude helper is closed");
            input.write(JSON.toJson(packet)); input.newLine(); input.flush();
        }
        void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try { receive(JsonParser.parseString(line).getAsJsonObject()); }
                    catch (RuntimeException invalid) { deliver(this,new Event(thread,"","delta","",object("kind","provider.warning","category","general","summary","Malformed Claude helper entry was isolated: " + invalid.getMessage()))); }
                }
                if (!stopped) fail("Claude helper disconnected; reconnect to resume context. Action outcomes may be unknown.");
            } catch (IOException error) { if (!stopped) fail("Claude helper connection failed; reconnect to resume context."); }
        }
        void receive(JsonObject packet) {
            // Capture native ownership before acknowledging startup or dispatching any work.
            rememberChildren();
            if (!packet.has("method")) {
                CompletableFuture<JsonObject> result = pending.get(string(packet,"id")); if (result == null) return;
                if (packet.has("error")) result.completeExceptionally(failure(packet.getAsJsonObject("error")));
                else result.complete(packet.getAsJsonObject("result"));
                return;
            }
            JsonObject params = packet.getAsJsonObject("params");
            if (!thread.equals(string(params,"threadId"))) return;
            synchronized(ClaudeConnector.this) { if (sessions.get(thread) != this || stopped) return; }
            String method = string(packet,"method"), turn = string(params,"turnId");
            if (method.equals("toolCall")) {
                String id = string(params,"id");
                CompletableFuture<JsonObject> call;
                try { call = tools.call(thread,turn,string(params,"name"),params.getAsJsonObject("arguments")); }
                catch (RuntimeException error) { call = CompletableFuture.failedFuture(error); }
                call.whenComplete((result,error) -> {
                    synchronized(ClaudeConnector.this) { if (sessions.get(thread) != this || stopped) return; }
                    request("respond",object("requestId",id,"result",error == null ? result : object("isError",true,"text","Tool call failed: " + unwrap(error).getMessage())));
                });
            } else if (method.equals("interaction")) {
                JsonObject payload = params.getAsJsonObject("payload"); SharedModel.validate("pendingInteractionPayloadSchema",payload);
                String id = thread + ":" + string(params,"id"); synchronized(ClaudeConnector.this) { interactions.put(id,this); }
                deliver(this,new Event(thread,turn,"interaction/request","",object("id",id,"payload",payload)));
            } else if (method.equals("event")) {
                String kind = string(params,"kind");
                if (kind.equals("connection/error")) kind = "session/disconnected";
                JsonObject data = params.getAsJsonObject("data").deepCopy();
                if (kind.equals("delta")) { SharedModel.validateDelta(data); track(data); }
                if (kind.equals("interaction/resolved")) {
                    String id = thread + ":" + string(data,"id"); data.addProperty("id",id);
                    synchronized(ClaudeConnector.this) { interactions.remove(id,this); }
                }
                if (kind.equals("session/disconnected")) {
                    String detail = string(params,"text");
                    if (!detail.isBlank()) terminalFailure = new RequestFailure(detail,"restartRecommended",false,data);
                    settle("failed");
                }
                deliver(this,new Event(thread,turn,kind,string(params,"text"),data));
                if (kind.equals("connection/error") || kind.equals("session/disconnected")) discard(this);
            } else throw new IllegalArgumentException("Unknown helper notification " + method);
        }
        synchronized void track(JsonObject delta) {
            String kind = string(delta,"kind");
            String turn = string(delta,"providerTurnId");
            if (kind.equals("turn.open")) turns.put(turn,delta.deepCopy());
            if (kind.equals("turn.boundary") || kind.equals("session.ended")) {
                String ended = kind.equals("session.ended") ? "" : turn;
                turns.remove(ended);
                items.entrySet().removeIf(entry -> string(entry.getValue(),"ownedTurn").equals(ended) && !entry.getValue().get("persistent").getAsBoolean());
            }
            if (!delta.has("key")) return;
            ItemKey key = new ItemKey(turn,delta.getAsJsonObject("key").deepCopy());
            if (kind.equals("item.textClose") || kind.equals("item.close") && !string(delta,"status").equals("pending")) { items.remove(key); return; }
            if (kind.equals("item.open") || kind.equals("item.close") || kind.equals("item.progress")) {
                JsonObject tracked = items.computeIfAbsent(key,unused -> object("ownedTurn",turn,"persistent",false));
                JsonObject snapshot = delta.has("item") ? delta.getAsJsonObject("item") : delta.has("snapshot") ? delta.getAsJsonObject("snapshot") : null;
                if (delta.has("presentation")) tracked.add("presentation",delta.get("presentation").deepCopy());
                if (snapshot != null) {
                    tracked.add("snapshot",snapshot.deepCopy());
                    tracked.addProperty("persistent",Set.of("backgroundTask","delegation").contains(string(snapshot,"type")));
                    if (Set.of("completed","failed","interrupted").contains(string(snapshot,"status"))) items.remove(key);
                }
            }
        }
        void settle(String status) {
            List<Event> settled = new ArrayList<>();
            synchronized(this) {
                for (var entry : new ArrayList<>(items.entrySet())) {
                    JsonObject close = object("kind","item.close","key",entry.getKey().key(),"status",status);
                    JsonObject tracked = entry.getValue();
                    if (tracked.has("snapshot")) {
                        JsonObject snapshot = tracked.getAsJsonObject("snapshot").deepCopy();
                        if (tracked.get("persistent").getAsBoolean()) snapshot.addProperty("status",status);
                        if (snapshot.has("taskStatus")) snapshot.addProperty("taskStatus","failed");
                        close.add("item",snapshot);
                    }
                    if (!close.has("item")) continue;
                    if (tracked.has("presentation")) close.add("presentation",tracked.get("presentation").deepCopy());
                    String turn = string(tracked,"ownedTurn"); if (!turn.isEmpty()) close.addProperty("providerTurnId",turn);
                    SharedModel.validateDelta(close); settled.add(new Event(thread,turn,"delta","",close));
                }
                items.clear();
                for (String turn : new ArrayList<>(turns.keySet())) if (!turn.isEmpty()) {
                    JsonObject boundary = object("kind","turn.boundary","status",status,"providerTurnId",turn);
                    settled.add(new Event(thread,turn,"delta","",boundary));
                }
                if (turns.containsKey("")) settled.add(new Event(thread,"","delta","",object("kind","session.ended","status",status)));
                turns.clear();
            }
            settled.forEach(event -> deliver(this,event));
        }
        void fail(String message) {
            synchronized(this) { if (stopped || failing) return; failing = true; }
            terminalFailure = new RequestFailure(message,"restartRecommended",false,object());
            settle("failed");
            deliver(this,new Event(thread,"","session/disconnected",message,object("recovery",object("kind","restartRecommended"),"retryable",false)));
            discard(this);
        }
        void rememberChildren() {
            Process child = process;
            if (child == null) return;
            child.descendants().forEach(ownedChildren::add);
            // Retained handles still identify our native process after a killed helper is reparented.
            for (ProcessHandle owned : new ArrayList<>(ownedChildren)) {
                if (owned.isAlive()) owned.descendants().forEach(ownedChildren::add);
                else ownedChildren.remove(owned);
            }
        }
        CompletableFuture<Void> terminate() {
            Process child; Path temporary;
            synchronized(this) {
                if (termination != null) return termination;
                stopped = true;
                termination = new CompletableFuture<>();
                child = process; temporary = directory;
            }
            writer.shutdownNow();
            pending.values().forEach(result -> result.completeExceptionally(terminalFailure)); pending.clear();
            CompletableFuture.runAsync(() -> {
                if (child != null) {
                    rememberChildren();
                    List<ProcessHandle> descendants = new ArrayList<>(ownedChildren);
                    descendants.forEach(ProcessHandle::destroy); child.destroy();
                    try { child.waitFor(500,TimeUnit.MILLISECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    descendants.forEach(handle -> { if (handle.isAlive()) handle.destroyForcibly(); }); if (child.isAlive()) child.destroyForcibly();
                    try {
                        CompletableFuture<?>[] exits = descendants.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
                        CompletableFuture.allOf(exits).thenCombine(child.onExit(),(unused,exited) -> null).get(4,TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CompletionException(interrupted); }
                    catch (ExecutionException | TimeoutException failure) { throw new CompletionException("Claude owned processes did not exit after termination",failure); }
                    ownedChildren.clear();
                }
                if (temporary != null) try { Files.deleteIfExists(temporary.resolve("bridge.mjs")); Files.deleteIfExists(temporary); } catch (IOException ignored) { temporary.toFile().deleteOnExit(); }
            }).whenComplete((unused,error) -> { if (error == null) termination.complete(null); else termination.completeExceptionally(error); });
            return termination;
        }
    }
    private record ItemKey(String turn,JsonObject key) {}
    private static RequestFailure failure(JsonObject error) {
        String recovery = string(error,"recovery");
        if (recovery.isEmpty()) recovery = switch(string(error,"category")) { case "unauthorized" -> "authRequired"; case "stale" -> "staleTurn"; case "rate_limited" -> "rateLimited"; default -> "restartRecommended"; };
        SharedModel.validate("providerRecoveryKindSchema",new JsonPrimitive(recovery));
        return new RequestFailure(string(error,"message"),recovery,error.has("rejected") && error.get("rejected").getAsBoolean(),error);
    }
    private static Path executable(String override,String name) throws IOException {
        String configured = System.getenv(override);
        if (configured != null && !configured.isBlank()) { Path result = Path.of(configured); if (Files.isExecutable(result)) return result; throw new IOException(override + " does not name an executable"); }
        List<Path> candidates = new ArrayList<>();
        for (String folder : System.getenv().getOrDefault("PATH","").split(File.pathSeparator)) if (!folder.isBlank()) candidates.add(Path.of(folder,name));
        Path home = Path.of(System.getProperty("user.home"));
        candidates.add(home.resolve(".local/bin/" + name)); candidates.add(Path.of("/opt/homebrew/bin",name)); candidates.add(Path.of("/usr/local/bin",name));
        if (name.equals("node")) {
            Path versions = home.resolve(".nvm/versions/node");
            if (Files.isDirectory(versions)) try (var entries = Files.list(versions)) { entries.sorted(Comparator.reverseOrder()).forEach(path -> candidates.add(path.resolve("bin/node"))); }
        }
        for (Path path : candidates) if (Files.isRegularFile(path) && Files.isExecutable(path)) return path;
        throw new IOException("Cannot find " + name + ". Configure " + override + " with its executable path.");
    }
    private static Throwable unwrap(Throwable error) { while (error instanceof CompletionException || error instanceof ExecutionException) error = error.getCause(); return error; }
    private static String string(JsonObject object,String key) { return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private static JsonObject object(Object... pairs) { JsonObject result = new JsonObject(); for (int i=0;i<pairs.length;i+=2) result.add((String)pairs[i],JSON.toJsonTree(pairs[i+1])); return result; }
}
