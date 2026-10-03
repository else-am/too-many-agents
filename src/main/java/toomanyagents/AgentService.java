package toomanyagents;

import toomanyagents.agent.AgentHarness;
import toomanyagents.agent.CodexAdapter;
import toomanyagents.agent.CodexConnector;
import toomanyagents.agent.ClaudeConnector;
import toomanyagents.agent.model.DeltaAssembler;
import toomanyagents.agent.model.SharedModel;
import toomanyagents.agent.history.ConversationHistory;
import toomanyagents.ui.AgentUiAccess;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Saved body/thread associations and serialized state for the native UI. */
final class AgentService implements AgentUiAccess, AutoCloseable {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private static final String QUESTION_REPLY = "[Too Many Agents question reply]\n";
    private final Path storage;
    private final GameAccess game;
    private final ProjectStore projects;
    private final ExecutorService projectJobs = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("too_many_agents-projects").factory());
    private final Supplier<String> worldSession;
    private final ExecutorService disk = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("too_many_agents-agents-save").factory());
    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Set<String> busyCheckouts = new HashSet<>();
    private final Set<String> busyProjects = new HashSet<>();
    private final CompletableFuture<Void> ready;
    private record Connection(String generation, AgentHarness harness) {}
    private final Map<String, Connection> connections = new HashMap<>();
    private String defaultPermissionMode = "accept-edits";
    private CompletableFuture<JsonObject> usageRead;
    private long usageReadMs;
    private final ScheduledExecutorService coordination = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("too_many_agents-coordination").factory());
    private final LinkedHashMap<String, JsonObject> profiles = new LinkedHashMap<>();
    private final Map<String, String> scriptTokens = new HashMap<>();
    private String bridgeUrl;
    private final Path scriptApi;

    synchronized void bridgeUrl(String url) { bridgeUrl = url; }
    private Path descriptor(Agent agent) { return storage.getParent().resolve("agents").resolve(agent.id).resolve("connection.json"); }
    private CompletableFuture<Void> scriptConnection(Agent agent, String expectedSession) {
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        synchronized(this) {
            if (!active(agent)) return failed("agent_not_active: " + agent.lifecycle);
            scriptTokens.values().removeIf(id -> id.equals(agent.id));
            scriptTokens.put(token,agent.id);
            agent.scriptSession = expectedSession;
        }
        var info = object("protocol",1,"url",bridgeUrl,"token",token,"agentId",agent.id,"session",expectedSession);
        return CompletableFuture.runAsync(() -> {
            try {
                var path = descriptor(agent); Files.createDirectories(path.getParent());
                var tmp = Files.createTempFile(path.getParent(),"connection-",".tmp");
                try {
                    if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    Files.writeString(tmp,JSON.toJson(info));
                    Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(tmp); }
            } catch(IOException e) { throw new CompletionException(e); }
        },disk);
    }
    CompletableFuture<JsonObject> scopedCall(String token, JsonObject request) {
        Agent agent;
        synchronized(this) {
            String id = scriptTokens.get(token);
            if(id == null) return failed("unauthorized");
            agent = require(id);
            if (!Objects.equals(agent.scriptSession,worldSession.get()) || !Objects.equals(text(request,"session"),agent.scriptSession)) return failed("world_session_changed");
            if (agent.stopping) return failed("agent_stopping");
            String operation = text(request, "operation");
            if (operation.equals("listTools")) return CompletableFuture.completedFuture(MinecraftStrategy.resolve(agent.strategy).catalog());
            if (!operation.isBlank()) return failed("unknown_operation");
            if (agent.turnSession == null || !agent.activeTurn || agent.toolScope == null) return failed("tool_turn_no_longer_active");
            String tool = text(request,"tool");
            if (MinecraftStrategy.resolve(agent.strategy).isAgentTool(tool)) return agentTool(agent, tool, obj(request,"arguments"));
            return MinecraftStrategy.resolve(agent.strategy).call(game, agent.body, agent.turnSession, agent.toolScope, tool, obj(request,"arguments")).thenApply(result -> filterDiscovery(agent,result));
        }
    }
    @Override public synchronized JsonArray profiles() {
        var result = new JsonArray();
        profiles.forEach((name,settings) -> result.add(object("name",name,"settings",withPermissions(settings, connection(providerId(settings)).normalizePermissions(settings)))));
        return result;
    }
    @Override public CompletableFuture<Void> saveProfile(String name, JsonObject settings) {
        if (name == null || name.isBlank() || name.length()>80) return failed("Profile name must contain 1–80 characters.");
        var copy = settings.deepCopy();
        validateSettings(copy);
        for (String key : List.of("color","project","directory","projectId","checkoutId","useWorktree","baseRef","title")) copy.remove(key); // Profiles share behavior, not folder associations.
        connection(providerId(copy)).normalizePermissions(copy).entrySet().forEach(entry -> copy.add(entry.getKey(), entry.getValue()));
        return ready.thenCompose(unused -> { synchronized(this) { profiles.put(name.strip(),copy); } return save(); });
    }
    private void validateSettings(JsonObject settings) {
        if (settings.has("color") && !AgentColor.valid(text(settings,"color"))) throw new IllegalArgumentException("Color must use #RRGGBB.");
        if (settings.has("communication") && !Set.of("none","children","project","any").contains(text(settings,"communication"))) throw new IllegalArgumentException("Communication must be none, children, project, or any.");
        String mode = text(settings,"mode");
        if (!mode.isBlank() && !List.of("survival","creative").contains(mode)) throw new IllegalArgumentException("Mode must be Survival or Creative.");
        for(String key : List.of("cheats","following","nativeSubagentsEnabled","minecraftAccess","useWorktree")) if(settings.has(key) && (!settings.get(key).isJsonPrimitive() || !settings.getAsJsonPrimitive(key).isBoolean())) throw new IllegalArgumentException(key+" must be a boolean.");
        if (!text(settings,"baseRef").isBlank() && (!settings.has("useWorktree") || !settings.get("useWorktree").getAsBoolean()))
            throw new IllegalArgumentException("A starting ref requires a new worktree.");
        String followReturn = text(settings,"followReturn");
        if (settings.has("followReturn") && (!settings.get("followReturn").isJsonPrimitive()
            || !settings.getAsJsonPrimitive("followReturn").isString() || !List.of("previous","always").contains(followReturn))) throw new IllegalArgumentException("Follow return must be previous or always.");
        if (settings.has("behaviors")) validateBehaviors(settings.get("behaviors"));
        if (text(settings,"title").length() > 80 || text(settings,"title").contains("\n")) throw new IllegalArgumentException("A title must be one line of at most 80 characters.");
        connection(providerId(settings)).normalizePermissions(settings);
    }
    /**
     * behaviors: {state: {type, target?}} for working, needs_input, done and idle. Types are stand, wander,
     * look (target "player" or {x,y,z}) and swing (target {x,y,z}). A missing state stands.
     */
    static void validateBehaviors(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Behaviors must be an object keyed by state.");
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!Set.of("working","needs_input","done","idle").contains(entry.getKey())) throw new IllegalArgumentException("Unknown behavior state: " + entry.getKey());
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Each behavior must be an object.");
            var behavior = entry.getValue().getAsJsonObject();
            String type = text(behavior,"type");
            var target = behavior.get("target");
            boolean block = target != null && target.isJsonObject() && List.of("x","y","z").stream().allMatch(axis -> {
                var n = target.getAsJsonObject().get(axis);
                return n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber() && n.getAsDouble() == Math.rint(n.getAsDouble()) && Math.abs(n.getAsDouble()) <= 30_000_000;
            });
            boolean player = target != null && target.isJsonPrimitive() && target.getAsString().equals("player");
            boolean valid = switch (type) {
                case "stand", "wander" -> target == null;
                case "look" -> block || player;
                case "swing" -> block;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid " + entry.getKey() + " behavior: use stand, wander, look at the player or a block, or swing at a block.");
        }
    }
    private static JsonObject withPermissions(JsonObject settings, JsonObject permissions) {
        var result = settings.deepCopy();
        permissions.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue().deepCopy()));
        return result;
    }
    @Override public CompletableFuture<Void> updateSettings(String id, JsonObject settings) {
        var copy = settings.deepCopy();
        if (!copy.has("providerId")) copy.addProperty("providerId",require(id).providerId);
        validateSettings(copy);
        if (copy.has("providerId") && !text(copy,"providerId").equals(require(id).providerId)) return failed("The provider is fixed for this conversation. Create another agent to change it.");
        copy.remove("providerId"); copy.remove("provider");
        if (copy.has("minecraftAccess") && copy.get("minecraftAccess").getAsBoolean() != minecraftAccess(require(id)))
            return failed("Minecraft access is fixed when the agent is spawned.");
        copy.remove("minecraftAccess");
        if (copy.has("strategy")) return failed("A strategy is fixed when the agent is spawned.");
        String expectedSession = worldSession.get();
        return ready.thenCompose(unused -> {
            Agent agent;
            JsonObject permissions;
            synchronized(this) {
                agent = require(id);
                if (agent.updatingSettings || agent.recoveringBody != null && !agent.recoveringBody.isDone()) return failed("Agent settings are already being saved.");
                var merged = agent.permissions.deepCopy();
                for (String key : permissionKeys(agent)) if (copy.has(key)) merged.add(key, copy.get(key));
                permissions = connection(agent).normalizePermissions(merged);
                agent.updatingSettings = true;
            }
            boolean bodySettings;
            synchronized (this) {
                var current = game.settings(agent.body);
                bodySettings = List.of("name","body","mode","cheats","following","followReturn","color","communication","behaviors").stream()
                    .anyMatch(key -> copy.has(key) && !Objects.equals(copy.get(key), current.get(key)));
            }
            var update = bodySettings ? game.updateSettings(agent.body,expectedSession,copy) : CompletableFuture.completedFuture(agent.body);
            return update.thenCompose(body -> {
                synchronized(this) {
                    agent.body = body;
                    if (bodySettings) { agent.settings = game.settings(body); agent.name = text(agent.settings,"name"); }
                    if (copy.has("permissionMode") && (!permissions.equals(agent.permissions) || copy.size() == 1))
                        defaultPermissionMode = text(permissions,"permissionMode");
                    agent.permissions = permissions;
                    if (copy.has("nativeSubagentsEnabled")) agent.nativeSubagentsEnabled = copy.get("nativeSubagentsEnabled").getAsBoolean();
                    String title = text(copy,"title").strip();
                    if (copy.has("title") && !title.equals(agent.taskTitle)) { agent.taskTitle = title; agent.taskTitleSource = title.isEmpty() ? "" : "set"; }
                }
                return save();
            }).thenCompose(saved -> applyPermissions(agent)).whenComplete((done, failure) -> { synchronized(this) {
                agent.updatingSettings = false;
                if (failure == null && agent.turnSession == null && !agent.stopping && !agent.holdMessages) sendNext(agent);
            } });
        });
    }
    /** Applies saved permissions to the open session when the provider can; otherwise they apply at the next turn. */
    private CompletableFuture<Void> applyPermissions(Agent agent) {
        AgentHarness.Session session;
        JsonObject from, to;
        synchronized (this) {
            session = agent.session; from = agent.sessionPermissions; to = agent.permissions.deepCopy();
            if (session == null || agent.opening != null || to.equals(from)) return CompletableFuture.completedFuture(null);
        }
        return connection(agent).updatePermissions(session, from, to).handle((applied, failure) -> {
            synchronized (this) {
                if (failure != null || !applied || agent.session != session) return null;
                agent.sessionPermissions = to;
                if (agent.turnSession != null) agent.turnPermissions = to.deepCopy();
            }
            return null;
        });
    }
    private String storageError = "";
    private String worldProjectChecked = "";
    private boolean closed, shuttingDown;

    private static final class Agent {
        String id, name, providerSessionId, model, effort, scriptSession;
        String projectId = "", checkoutId = "";
        String taskTitle = "", taskTitleSource = "";
        String providerId = "codex";
        DeltaAssembler assembler;
        ConversationHistory history;
        String historyError = "";
        int backgroundRunning;
        String serviceTier = "default";
        JsonElement strategy = MinecraftStrategy.CURRENT.reference();
        JsonObject settings = new JsonObject();
        JsonObject permissions = new JsonObject();
        JsonObject sessionPermissions = new JsonObject(), turnPermissions = new JsonObject();
        Path sessionDirectory;
        List<Path> sessionAdditionalDirectories = List.of();
        JsonObject recovery = new JsonObject();
        GameAccess.Body body;
        String status = "idle", turnSession, activeTurnId, incomingNativeTurnId;
        GameAccess.ToolScope toolScope;
        String lifecycle = "active", removalError = "";
        boolean bodyLost, bodyRemoved, archiveRequested, threadArchived, removalInProgress, conversationArchived;
        boolean historyRequested, updatingSettings;
        boolean nativeSubagentsEnabled, holdMessages, delivering, provisioning;
        String parentId = "";
        final List<JsonObject> mailbox = new ArrayList<>();
        final Set<String> suppressedNotices = new HashSet<>();
        boolean resumeRequired = true;
        boolean activeTurn;
        long submission;
        long replyVersion, readVersion;
        long errorVersion, readErrorVersion;
        String lastError = "";
        boolean stopping;
        AgentHarness.Session session;
        CompletableFuture<AgentHarness.Session> opening;
        CompletableFuture<Void> recoveringBody;

        final LinkedHashMap<String, Pending> requests = new LinkedHashMap<>();
        final LinkedHashMap<String, JsonObject> asyncQuestions = new LinkedHashMap<>();
        final Set<String> answeredQuestions = new LinkedHashSet<>();
        final Set<String> responsesInFlight = new HashSet<>();
        final List<QueuedMessage> queuedMessages = new ArrayList<>();
    }
    private record QueuedMessage(String id, String text, String model, String effort, String serviceTier, JsonObject pointing, String worldSession, List<String> images) {}
    private record Pending(String rpcId, String kind, JsonObject params, String questionId,
                           JsonObject answers, JsonObject display, Map<String, JsonObject> responses) {}

    AgentService(Path storageDirectory, GameAccess game, Supplier<String> session) {
        this.storage = storageDirectory.resolve("agents-v4.json");
        this.game = game;
        this.projects = new ProjectStore(storageDirectory);
        this.scriptApi = storageDirectory.resolve("minecraft.mjs").toAbsolutePath();
        profiles.put("Survival",object("mode","survival","cheats",false,"following",false,"body","minecraft:villager"));
        profiles.put("Creative",object("mode","creative","cheats",false,"following",false,"body","minecraft:villager"));
        this.worldSession = session;
        ready = CompletableFuture.runAsync(() -> {
            try { projects.load(); } catch (IOException e) { throw new CompletionException(e); }
            load();
            try (var resource = AgentService.class.getResourceAsStream(MinecraftStrategy.CURRENT.clientResource())) {
                Files.createDirectories(storageDirectory);
                if (resource == null) throw new IOException("Bundled JavaScript API missing");
                Files.copy(resource,scriptApi,StandardCopyOption.REPLACE_EXISTING);
            } catch(IOException failure) { throw new CompletionException(failure); }
        }, disk);
        ready.whenComplete((unused, failure) -> { if (failure != null) synchronized (this) { storageError = message(failure); } });
        coordination.scheduleWithFixedDelay(this::pumpMessages, 1, 1, TimeUnit.SECONDS);
    }

    @Override public synchronized String defaultPermissionMode() { return defaultPermissionMode; }

    @Override public synchronized CompletableFuture<JsonObject> usage() {
        long now = System.currentTimeMillis();
        if (usageRead == null || usageRead.isDone() && now - usageReadMs >= 60_000) {
            usageReadMs = now;
            usageRead = ready.thenCompose(unused -> {
                var reads = new ArrayList<CompletableFuture<JsonObject>>();
                for (String provider : List.of("codex", "claude")) {
                    reads.add(CompletableFuture.supplyAsync(() -> connection(provider))
                        .thenCompose(AgentHarness::usage).orTimeout(45, TimeUnit.SECONDS).handle((value, failure) -> {
                        JsonObject row = failure == null ? value.deepCopy() : object("windows", List.of(), "message", "Usage unavailable");
                        row.addProperty("providerId", provider);
                        row.addProperty("checkedAtMs", System.currentTimeMillis());
                        return row;
                    }));
                }
                return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(done ->
                    object("providers", reads.stream().map(CompletableFuture::join).toList()));
            });
        }
        return usageRead.thenApply(JsonObject::deepCopy);
    }

    @Override public synchronized JsonObject projects() { var result = projects.snapshot(); result.add("world",game.worldInfo()); return result; }

    /** Each time a world opens, create its Minecraft project if missing, including worlds made before the mod. */
    synchronized void ensureWorldProject() {
        if (!ready.isDone() || ready.isCompletedExceptionally()) return;
        String world, key;
        try { world = game.currentWorldId(); } catch (IllegalStateException moved) { return; } // the moved/copied prompt decides first
        if (world == null || (key = world + "@" + worldSession.get()).equals(worldProjectChecked)) return;
        worldProjectChecked = key;
        CompletableFuture.runAsync(() -> {
            try { projects.minecraft(world); } catch (IOException failure) { LOG.warn("Could not create this world's Minecraft project", failure); }
        }, disk);
    }

    @Override public CompletableFuture<JsonObject> projectCommand(JsonObject request) {
        var copy = request.deepCopy();
        String operation = text(copy,"operation");
        if (Set.of("world-resolve","bounds-set","bounds-clear","station-create","station-update","station-delete","station-assign","station-release").contains(operation)) {
            String expectedSession = worldSession.get();
            return ready.thenCompose(unused -> {
                if (Set.of("bounds-set","bounds-clear","station-create").contains(operation)) projects.project(text(copy,"projectId"));
                if (!text(copy,"agentId").isBlank()) synchronized (this) { copy.addProperty("agentProjectId", require(text(copy,"agentId")).projectId); }
                return game.worldCommand(copy,expectedSession).thenApply(result -> { migrateWorldBodies(); return result; });
            });
        }
        return ready.thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            try {
                String projectId = text(copy,"projectId");
                return switch (operation) {
                    case "create" -> {
                        var checkout = projects.importDirectory(text(copy,"directory").isBlank() ? text(copy,"primaryDirectory") : text(copy,"directory"),"");
                        var settings = copy.deepCopy(); settings.addProperty("projectId",text(checkout,"projectId"));
                        projects.configure(settings);
                        yield checkout;
                    }
                    case "configure" -> configureProject(copy);
                    case "remove" -> {
                        synchronized(this) {
                            for (var agent : agents.values()) if (agent.projectId.equals(projectId))
                                throw new IllegalStateException("This project is still referenced by " + agent.name + ". Remove its saved agent association first.");
                            if (busyProjects.contains(projectId) || busyCheckouts.stream().anyMatch(key -> key.startsWith(projectId + ":")))
                                throw new IllegalStateException("Wait for project operations to finish.");
                            yield projects.removeProject(projectId);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown project operation.");
                };
            } catch(Exception failure) { throw new CompletionException(failure); }
        },projectJobs));
    }

    private JsonObject configureProject(JsonObject request) throws Exception {
        String projectId = text(request,"projectId");
        var previous = projects.project(projectId);
        boolean folders = request.has("primaryDirectory") && !request.get("primaryDirectory").equals(previous.get("primaryDirectory"))
            || request.has("additionalDirectories") && !request.get("additionalDirectories").equals(previous.get("additionalDirectories"));
        if (!folders) return projects.configure(request);
        List<Agent> refresh;
        synchronized(this) {
            if (busyProjects.contains(projectId) || busyCheckouts.stream().anyMatch(key -> key.startsWith(projectId + ":")))
                throw new IllegalStateException("Wait for project operations before changing folders.");
            refresh = agents.values().stream().filter(agent -> agent.projectId.equals(projectId) && active(agent)).toList();
            for (var agent : refresh) if (agent.turnSession != null || agent.backgroundRunning > 0 || agent.provisioning
                    || agent.updatingSettings || agent.opening != null || !agent.requests.isEmpty())
                throw new IllegalStateException("Stop active work before changing folders: " + agent.name);
            busyProjects.add(projectId);
        }
        try {
            var result = projects.configure(request);
            for (var agent : refresh) if (agent.session != null) ensureSession(agent).join();
            save().join();
            return result;
        } finally { synchronized(this) { busyProjects.remove(projectId); } }
    }

    private static String checkoutKey(String projectId, String checkoutId) { return projectId + ":" + checkoutId; }
    private boolean checkoutBusy(Agent agent) { return busyProjects.contains(agent.projectId) || busyCheckouts.contains(checkoutKey(agent.projectId,agent.checkoutId)); }
    private String directory(Agent agent) { return text(projects.checkout(agent.projectId,agent.checkoutId),"directory"); }
    private List<Path> additionalDirectories(Agent agent) {
        return array(projects.project(agent.projectId),"additionalDirectories").asList().stream().map(value -> Path.of(value.getAsString())).toList();
    }

    synchronized void migrateWorldBodies() {
        if (!ready.isDone() || ready.isCompletedExceptionally()) return;
        var info = game.worldInfo();
        if (!info.has("id") || info.get("needsDecision").getAsBoolean()) return;
        String id = text(info,"id");
        boolean changed = false;
        for (var agent : agents.values()) if (game.belongsToCurrentWorld(agent.body) && !agent.body.world().equals(id)) {
            agent.body = new GameAccess.Body(agent.body.entityUuid(),id,agent.body.dimension()); changed = true;
        }
        if (changed) save();
    }

    synchronized String findByBody(UUID body) {
        return agents.values().stream().filter(a -> a.body != null && a.body.entityUuid().equals(body.toString()) && game.belongsToCurrentWorld(a.body))
                .map(a -> a.id).findFirst().orElse(null);
    }

    /** Each active agent's project and coarse activity, read by the game thread every tick. */
    synchronized Map<String,GameAccess.AgentState> agentStates() {
        if (!ready.isDone() || ready.isCompletedExceptionally() || closed || shuttingDown) return Map.of();
        var result = new HashMap<String,GameAccess.AgentState>();
        for (var agent : agents.values()) if (active(agent)) result.put(agent.id, new GameAccess.AgentState(agent.projectId, activity(agent)));
        return result;
    }
    /** working, needs_input, done or idle, taken from the agent's real state. */
    private static String activity(Agent agent) {
        if (!agent.requests.isEmpty() || !agent.asyncQuestions.isEmpty() || Set.of("waiting","error").contains(agent.status)
            || agent.turnSession == null && !agent.lastError.isBlank()) return "needs_input";
        if (working(agent)) return "working";
        return agent.replyVersion > agent.readVersion ? "done" : "idle";
    }

    private static boolean working(Agent agent) {
        return agent.turnSession != null || agent.activeTurn || agent.backgroundRunning > 0
            || Set.of("starting", "running", "background", "stopping").contains(agent.status);
    }

    synchronized Set<String> retainedBodies() {
        if (!ready.isDone() || ready.isCompletedExceptionally() || closed || shuttingDown) return null;
        return agents.values().stream().filter(agent -> !agent.bodyRemoved || agent.removalInProgress)
            .map(agent -> agent.id).collect(java.util.stream.Collectors.toSet());
    }

    @Override public synchronized JsonArray list() {
        JsonArray result = new JsonArray();
        for (Agent agent : agents.values()) {
            result.add(snapshot(agent.id));
        }
        return result;
    }

    /** Lightweight state for the HUD and mention completion; never opens a harness conversation. */
    @Override public synchronized JsonArray worldAgents() {
        var result = new JsonArray();
        if (!ready.isDone() || ready.isCompletedExceptionally() || worldSession.get() == null) return result;
        for (var agent : agents.values()) {
            if (!active(agent) || agent.body == null || agent.bodyRemoved || !game.belongsToCurrentWorld(agent.body)) continue;
            var state = object("id", agent.id, "name", agent.name, "taskTitle", agent.taskTitle, "body", agent.body,
                "color", color(agent), "communication", communication(agent), "bodyType", text(agent.settings,"body"), "model", agent.model, "effort", agent.effort, "serviceTier", agent.serviceTier,
                "status", agent.status, "turnActive", agent.turnSession != null || agent.backgroundRunning > 0);
            addAttention(agent, state);
            result.add(state);
        }
        return result;
    }

    private void addAttention(Agent agent, JsonObject state) {
        String attention = "";
        if (agent.requests.values().stream().anyMatch(p -> text(p.display,"kind").equals("approval"))) attention = "approval";
        else if (!agent.requests.isEmpty() || !agent.asyncQuestions.isEmpty()) attention = "input";
        else if (!agent.lastError.isBlank() && agent.errorVersion > agent.readErrorVersion) attention = "error";
        state.addProperty("attention", attention);
        state.addProperty("unread", !working(agent) && agent.replyVersion > agent.readVersion);
        state.addProperty("replyVersion", agent.replyVersion);
        state.addProperty("errorVersion", agent.errorVersion);
        state.addProperty("bodyLoaded", active(agent) && agent.body != null && game.belongsToCurrentWorld(agent.body) && !game.cached(agent.body).isEmpty());
        state.addProperty("waitingForGame", game.isPaused() && agent.toolScope != null && agent.toolScope.pendingCount() > 0 && !agent.stopping);
        if (!agent.lastError.isBlank()) state.addProperty("error",agent.lastError);
    }

    @Override public synchronized void markErrorRead(String id, long errorVersion) {
        var agent = agents.get(id);
        if (agent == null || !active(agent) || closed) return;
        agent.readErrorVersion = Math.max(agent.readErrorVersion, Math.min(errorVersion, agent.errorVersion));
    }

    @Override public synchronized void markRead(String id, long replyVersion) {
        var agent = agents.get(id);
        if (agent == null || !active(agent) || closed) return;
        long seen = Math.max(0, Math.min(replyVersion, agent.replyVersion));
        if (seen <= agent.readVersion) return;
        agent.readVersion = seen;
        // Streaming reads are persisted when the turn ends, not once per text delta.
        if (agent.turnSession == null) save();
    }

    @Override public synchronized CompletableFuture<Void> openInventory(String id) {
        Agent agent = require(id);
        if (!active(agent) || !minecraftAccess(agent)) return failed("Inventory is only available for active Minecraft agents.");
        return game.openInventory(agent.body, worldSession.get(), id);
    }

    @Override public CompletableFuture<JsonObject> inventory(String id) {
        String session = worldSession.get();
        return ready.thenCompose(unused -> {
            synchronized (this) { return game.inventory(require(id).body, session); }
        });
    }

    CompletableFuture<Void> sendMention(String id, String message, JsonObject pointing) {
        var captured = pointing == null ? new JsonObject() : pointing.deepCopy();
        captured.addProperty("source", "minecraft_chat");
        return ready.thenCompose(unused -> {
            synchronized (this) {
                var agent = require(id);
                if (!game.belongsToCurrentWorld(agent.body)) return failed("That agent is not in this world.");
                return send(id, message, agent.model, agent.effort, captured, "queue");
            }
        });
    }

    @Override public CompletableFuture<JsonObject> catalog() { return catalog("codex"); }
    @Override public CompletableFuture<JsonObject> catalog(String providerId) {
        return ready.thenCompose(unused -> {
            AgentHarness connection = connection(providerId);
            return connection.connect().thenCombine(game.bodies(), (models, bodies) -> {
                JsonArray choices = new JsonArray();
                models.forEach(model -> choices.add(object("id", model.id(), "name", model.label(), "efforts", model.efforts(),
                    "defaultEffort", model.defaultEffort(), "serviceTiers", model.serviceTiers(),"descriptor",model.descriptor(),"supportsAutoMode",model.supportsAutoMode())));
                JsonArray types = new JsonArray();
                for (JsonElement body : bodies) types.add(text(body.getAsJsonObject(), "id"));
                return object("models", choices, "bodies", types, "strategies", List.of(MinecraftStrategy.CURRENT.description()),
                    "provider", connection.description(), "providers", List.of(connection("codex").description(), connection("claude").description()));
            });
        });
    }
    private synchronized AgentHarness connection(Agent agent) { return connection(agent.providerId); }
    private synchronized AgentHarness connection(String providerId) {
        if (closed) throw new IllegalStateException("Agent service is closed.");
        Connection existing = connections.get(providerId);
        if (existing != null) return existing.harness();
        String generation = UUID.randomUUID().toString();
        AgentHarness.ToolHandler toolHandler = (thread,turn,tool,args) -> worldTool(providerId,generation,thread,turn,tool,args);
        java.util.function.Consumer<AgentHarness.Event> events = event -> harnessEvent(providerId,generation,event);
        AgentHarness harness = switch (providerId) {
            case "codex" -> new CodexConnector(CodexAdapter.defaultExecutable(), toolHandler, events);
            case "claude" -> new ClaudeConnector(toolHandler, events);
            default -> throw new IllegalArgumentException("Unsupported provider: " + providerId);
        };
        connections.put(providerId, new Connection(generation, harness));
        return harness;
    }
    private static String providerId(JsonObject settings) { String id = text(settings,"providerId"); return id.isBlank() ? "codex" : id; }
    private List<String> permissionKeys(Agent agent) {
        List<String> keys = new ArrayList<>();
        for (var field : array(connection(agent).description(), "permissionFields")) keys.add(text(field.getAsJsonObject(),"key"));
        return keys;
    }
    private AgentHarness.Options options(Agent agent) {
        return new AgentHarness.Options(Path.of(directory(agent)), additionalDirectories(agent), instructions(agent), agent.model, agent.effort,
            agent.turnSession == null ? agent.permissions : agent.turnPermissions,
            MinecraftStrategy.resolve(agent.strategy).tools(), agent.serviceTier, agent.nativeSubagentsEnabled);
    }
    private void initializeModel(Agent agent) {
        agent.history = new ConversationHistory(historyDirectory(agent),agent.id);
        agent.assembler = new DeltaAssembler(agent.id,agent.providerId,"da"+UUID.randomUUID().toString().substring(0,8),0,0,System::currentTimeMillis,event -> sharedEvent(agent,event));
        JsonObject provider = connection(agent).description();
        agent.assembler.configureExtensions(text(provider,"pluginId"),obj(provider,"extensionSchemas"));
    }

    @Override public CompletableFuture<String> spawn(JsonObject spec) {
        String expectedSession = worldSession.get();
        return ready.thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            var resolved = spec.deepCopy();
            if (!resolved.has("permissionMode")) resolved.addProperty("permissionMode",defaultPermissionMode());
            try {
                var checkout = projects.select(resolved,game.currentWorldId(),"");
                resolved.addProperty("projectId",text(checkout,"projectId")); resolved.addProperty("checkoutId",text(checkout,"id"));
                var selectedProject = projects.project(text(checkout,"projectId"));
                if (!text(selectedProject,"minecraftWorldId").isBlank() && text(selectedProject,"name").equals("Minecraft"))
                    selectedProject = projects.configure(object("projectId",text(selectedProject,"id"),"name","Minecraft - " + text(game.worldInfo(),"name")));
                var defaults = obj(selectedProject,"defaults");
                defaults.entrySet().forEach(entry -> { if(text(resolved,entry.getKey()).isBlank()) resolved.add(entry.getKey(),entry.getValue().deepCopy()); });
            } catch(IOException e) { throw new CompletionException(e); }
            return resolved;
        },disk)).thenCompose(resolved -> Objects.equals(expectedSession,worldSession.get()) ? spawn(resolved,null,null,"") : failed("World changed before agent creation."));
    }

    /** A child's station is assigned before its body spawns, so the body appears inside it; a failed creation frees it. */
    private CompletableFuture<String> spawn(JsonObject spec, Agent parent, GameAccess.ToolScope callerScope, String stationId) {
        var settings = spec.deepCopy(); validateSettings(settings);
        // Creation without a chosen name or body gets random starters.
        if (text(settings,"name").isBlank()) {
            var taken = new HashSet<String>();
            synchronized (this) { for (Agent other : agents.values()) taken.add(other.name); }
            settings.addProperty("name", StarterAgents.name(taken));
        }
        if (text(settings,"body").isBlank()) settings.addProperty("body", StarterAgents.body());
        String projectId = text(settings, "projectId");
        if (!projectId.isBlank()) {
            boolean worldProject = !text(projects.project(projectId), "minecraftWorldId").isBlank();
            if (worldProject || !settings.has("minecraftAccess") && !settings.has("strategy"))
                settings.addProperty("minecraftAccess", worldProject);
        }
        String name=text(settings,"name"),body=text(settings,"body"),model=text(settings,"model"),effort=text(settings,"effort");
        if (!settings.has("mode")) settings.addProperty("mode","survival");
        if (!settings.has("cheats")) settings.addProperty("cheats",false);
        if (!settings.has("following")) settings.addProperty("following",false);
        String expectedSession = worldSession.get();
        if (expectedSession == null) return failed("Enter a singleplayer world before spawning an agent.");
        Agent agent = new Agent();
        if (settings.has("minecraftAccess") && !settings.get("minecraftAccess").getAsBoolean()) agent.strategy = MinecraftStrategy.WORK.reference();
        if (settings.has("strategy")) agent.strategy = MinecraftStrategy.resolve(settings.get("strategy")).reference();
        if (settings.has("minecraftAccess") && settings.get("minecraftAccess").getAsBoolean() != minecraftAccess(agent))
            return failed("Minecraft access does not match the selected strategy.");
        settings.remove("minecraftAccess");
        settings.remove("strategy");
        agent.id = UUID.randomUUID().toString();
        if (!settings.has("communication")) settings.addProperty("communication", "project");
        agent.settings = settings;
        agent.nativeSubagentsEnabled = settings.has("nativeSubagentsEnabled") && settings.get("nativeSubagentsEnabled").getAsBoolean();
        agent.parentId = parent == null ? "" : parent.id;
        agent.provisioning = true;
        String task = text(settings,"initialTask");
        String title = text(settings,"title").strip();
        if (!title.isEmpty()) { agent.taskTitle = title; agent.taskTitleSource = "set"; }
        settings.remove("title");
        if (parent != null) agent.mailbox.add(mail(UUID.randomUUID().toString(), parent.id, "task", task, "queue"));
        settings.remove("initialTask");
        agent.providerId = providerId(settings);
        initializeModel(agent);
        agent.permissions = connection(providerId(settings)).normalizePermissions(settings);
        agent.name = name == null ? "" : name.strip();
        if (agent.name.isBlank() || agent.name.length() > 80) return failed("Agent name must contain 1–80 characters.");
        agent.model = model;
        agent.effort = effort;
        agent.serviceTier = tier(text(settings, "serviceTier"));
        var assigned = stationId.isBlank() ? CompletableFuture.<JsonObject>completedFuture(null)
            : game.worldCommand(object("operation","station-assign","stationId",stationId,"agentId",agent.id,"agentProjectId",parent.projectId),expectedSession);
        return assigned.thenCompose(unused -> ready).thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            try {
                if (!Objects.equals(expectedSession,worldSession.get())) throw new IllegalStateException("World changed before agent creation.");
                var checkout = projects.select(settings, game.currentWorldId(), parent == null ? "" : parent.projectId);
                agent.projectId = text(checkout,"projectId"); agent.checkoutId = text(checkout,"id");
                if (settings.has("useWorktree") && settings.get("useWorktree").getAsBoolean()) {
                    String sourceKey = checkoutKey(agent.projectId,agent.checkoutId);
                    synchronized(this) {
                        if (checkoutBusy(agent)) throw new IllegalStateException("Project folders or checkout are being changed.");
                        busyCheckouts.add(sourceKey);
                    }
                    try {
                        checkout = projects.createWorktree(object("projectId",agent.projectId,"sourceCheckoutId",agent.checkoutId,"baseRef",text(settings,"baseRef"),"task",!title.isEmpty() ? title : task.isBlank() ? agent.name : task));
                        agent.checkoutId = text(checkout,"id");
                    } finally { synchronized(this) { busyCheckouts.remove(sourceKey); } }
                }
                if (!Objects.equals(expectedSession,worldSession.get())) throw new IllegalStateException("World changed before agent creation; any created worktree is kept on disk.");
            } catch (Exception failure) { throw new CompletionException(failure); }
            return connection(agent);
        }, projectJobs)).thenCompose(connection -> { synchronized(this) {
            var checkout = projects.checkout(agent.projectId,agent.checkoutId);
            if (checkoutBusy(agent)) return failed("Checkout is busy.");
            if (!settings.has("color")) settings.addProperty("color", AgentColor.random(agents.values().stream().map(AgentService::color).collect(java.util.stream.Collectors.toSet())));
            agents.put(agent.id, agent);
        } return agent.history.load().thenCompose(loaded -> selection(connection, model, effort, agent.serviceTier, agent.permissions))
            .thenCompose(unused -> {
                var sessionOptions = options(agent);
                agent.sessionPermissions = sessionOptions.permissions().deepCopy();
                agent.sessionDirectory = sessionOptions.directory();
                agent.sessionAdditionalDirectories = sessionOptions.additionalDirectories();
                return connection.start(agent.id, sessionOptions);
            }); })
            .thenCompose(session -> {
                agent.session = session;
                agent.providerSessionId = session.providerSessionId();
                agent.resumeRequired = session.restorable();
                agent.model = session.model();
                agent.effort = session.effort();
                agent.serviceTier = tier(session.serviceTier());
                return parent == null ? game.spawn(agent.name, body, agent.id, agent.projectId, expectedSession)
                    : game.spawnNear(agent.name, body, agent.id, agent.projectId, parent.body, expectedSession, callerScope);
            }).thenCompose(spawned -> {
                agent.body = spawned;
                return game.updateSettings(spawned, expectedSession, settings).thenCompose(updated -> {
                    agent.body = updated;
                    agent.settings = game.settings(updated);
                    synchronized (this) {
                        agents.put(agent.id, agent);
                        if (parent == null) defaultPermissionMode = text(agent.permissions,"permissionMode");
                    }
                    return agent.history.flush().thenCompose(saved -> save()).thenCompose(saved -> scriptConnection(agent,expectedSession)).thenApply(saved -> { synchronized (this) { agent.provisioning = false; } return agent.id; });
                }).exceptionallyCompose(failure -> {
                    // Only this newly spawned body belongs to the failed creation attempt.
                    return game.remove(spawned, expectedSession).handle((unused, rollbackFailure) -> {
                        synchronized (this) {
                            if (rollbackFailure == null) agents.remove(agent.id);
                            else { agent.status = "error"; add(agent, "system", "Saving failed; this body remains associated in memory. " + message(failure)); }
                        }
                        throw new CompletionException(failure);
                    });
                });
            }).exceptionallyCompose(failure -> {
                synchronized (this) {
                    if (agent.body != null && agents.containsKey(agent.id)) return CompletableFuture.failedFuture(failure);
                    agents.remove(agent.id);
                }
                var released = agent.session == null ? CompletableFuture.<Void>completedFuture(null) : connection(agent).release(agent.session);
                return released.handle((ignored,cleanupFailure) -> null).thenCompose(ignored -> agent.history.closeAsync())
                    .handle((ignored,cleanupFailure) -> null).thenCompose(ignored -> releaseStation(agent.id,expectedSession))
                    .handle((ignored,stationFailure) -> {
                        if (stationFailure == null) throw new CompletionException(failure);
                        throw new CompletionException(new IllegalStateException(message(failure) + " Its station could not be freed: " + message(stationFailure)));
                    });
            });
    }

    @Override public synchronized JsonObject snapshot(String id) {
        Agent agent = agents.get(id);
        if (agent == null) return object("id", id, "status", ready.isDone() ? "unavailable" : "loading", "messages", List.of(), "requests", List.of());
        JsonArray requests = new JsonArray();
        agent.requests.values().forEach(request -> {
            boolean answered = request.kind.equals("user_question") && request.answers.has(request.questionId);
            boolean unanswered = agent.requests.values().stream().anyMatch(other -> other.rpcId.equals(request.rpcId) && !other.answers.has(other.questionId));
            if (!answered || !unanswered) requests.add(request.display.deepCopy());
        });
        agent.asyncQuestions.values().forEach(question -> requests.add(question.deepCopy()));
        JsonObject result = object("id", agent.id, "name", agent.name, "directory", directory(agent), "projectId",agent.projectId,"checkoutId",agent.checkoutId,
                "model", agent.model, "effort", agent.effort, "serviceTier", agent.serviceTier, "threadId", agent.id, "providerSessionId", agent.providerSessionId, "providerId", agent.providerId, "provider", connection(agent).description(), "strategy", agent.strategy,
                "body", agent.body, "status", agent.status, "requests", requests,
                "lifecycle", agent.lifecycle, "conversationArchived", agent.conversationArchived, "bodyRemoved", agent.bodyRemoved, "bodyLost", agent.bodyLost, "archiveRequested", agent.archiveRequested, "threadArchived", agent.threadArchived);
        result.addProperty("currentWorld", game.belongsToCurrentWorld(agent.body));
        result.addProperty("projectName",text(projects.project(agent.projectId),"name"));
        result.add("checkout",projects.checkout(agent.projectId,agent.checkoutId));
        result.addProperty("checkoutShared",agents.values().stream().anyMatch(other -> other != agent && active(other) && other.projectId.equals(agent.projectId) && other.checkoutId.equals(agent.checkoutId)));
        result.addProperty("taskTitle", agent.taskTitle);
        result.addProperty("taskTitleSource", agent.taskTitleSource);
        result.addProperty("resumeRequired", agent.resumeRequired);
        result.addProperty("parentId", agent.parentId);
        result.addProperty("color", color(agent));
        result.addProperty("communication", communication(agent));
        result.addProperty("nativeSubagentsEnabled", agent.nativeSubagentsEnabled);
        result.addProperty("minecraftAccess", minecraftAccess(agent));
        result.addProperty("messagesHeld", agent.holdMessages);
        result.add("mailbox", JSON.toJsonTree(agent.mailbox));
        result.add("children", JSON.toJsonTree(agents.values().stream().filter(child -> child.parentId.equals(agent.id)).map(child -> child.id).toList()));
        result.addProperty("gamePaused", game.isPaused());
        addAttention(agent, result);
        result.addProperty("turnActive", agent.turnSession != null || agent.backgroundRunning > 0);
        result.addProperty("permissionsPending", (agent.turnSession != null || agent.backgroundRunning > 0)
            && !agent.permissions.equals(agent.turnPermissions));
        result.addProperty("backgroundRunning",agent.backgroundRunning);
        result.addProperty("canSteer", agent.activeTurn && agent.activeTurnId != null && !agent.stopping && text(obj(connection(agent).description(),"capabilities"),"steerMode").equals("inject"));
        result.add("conversation", agent.history.stateSnapshot());
        result.add("recovery",agent.recovery.deepCopy());
        if (!agent.historyError.isBlank()) result.addProperty("error",agent.historyError);
        result.add("queuedMessages", JSON.toJsonTree(agent.queuedMessages));
        result.addProperty("waitingForGame", game.isPaused() && agent.toolScope != null && agent.toolScope.pendingCount() > 0 && !agent.stopping);
        result.addProperty("pendingWorldTools", agent.toolScope == null ? 0 : agent.toolScope.pendingCount());
        if (!agent.removalError.isBlank()) result.addProperty("error", agent.removalError);
        var live = active(agent) && agent.body != null ? game.cached(agent.body) : new JsonObject();
        if (!live.isEmpty()) agent.settings = game.settings(agent.body);
        var exposedSettings = withPermissions(agent.settings, agent.permissions);
        exposedSettings.addProperty("nativeSubagentsEnabled", agent.nativeSubagentsEnabled);
        exposedSettings.addProperty("minecraftAccess", minecraftAccess(agent));
        result.add("settings",exposedSettings);
        result.addProperty("bodyType",text(agent.settings,"body"));
        result.addProperty("following", active(agent) && agent.body != null && game.following(agent.body));
        if (live.has("action")) result.add("action",live.get("action").deepCopy());
        result.addProperty("followingSuspended",live.has("followingSuspended") && live.get("followingSuspended").getAsBoolean());
        result.addProperty("followPauseReason",text(live,"followPauseReason"));
        if (!storageError.isBlank()) result.addProperty("error", agent.removalError.isBlank() ? storageError : agent.removalError + " " + storageError);
        return result;
    }

    synchronized void bodyLost(GameAccess.Body body) {
        for (var agent : agents.values()) {
            if (!active(agent) || !Objects.equals(agent.body, body)) continue;
            agent.bodyLost = true;
            save().exceptionally(failure -> { synchronized (this) { fail(agent, failure); } return null; });
        }
    }

    @Override public CompletableFuture<Void> checkBody(String id) {
        return ready.thenCompose(unused -> {
            synchronized (this) {
                var agent = agents.get(id);
                if (agent == null || !active(agent) || !agent.bodyLost || !game.belongsToCurrentWorld(agent.body))
                    return CompletableFuture.completedFuture(null);
                if (agent.recoveringBody != null) return agent.recoveringBody;
                if (agent.updatingSettings || agent.turnSession != null) return failed("Wait for the agent's current work before recovering its body.");
                var settings = agent.settings.deepCopy();
                settings.addProperty("name", agent.name);
                var recovery = game.recoverBody(agent.body, agent.id, agent.projectId, settings, worldSession.get()).thenCompose(recovered -> {
                    synchronized (this) {
                        agent.bodyLost = false;
                        agent.settings = game.settings(agent.body);
                        agent.lastError = "";
                        agent.status = "idle";
                        add(agent, "system", "Recovered the lost NPC body. Its conversation is unchanged; lost inventory was not recreated.");
                    }
                    return save();
                });
                agent.recoveringBody = recovery;
                recovery.whenComplete((done, failure) -> { synchronized (this) { if (agent.recoveringBody == recovery) agent.recoveringBody = null; } });
                return recovery;
            }
        });
    }

    @Override public CompletableFuture<Void> setFollowing(String id, boolean following) {
        String expectedSession = worldSession.get();
        return ready.thenCompose(unused -> {
            Agent agent;
            synchronized (this) { agent = require(id); }
            return game.setFollowing(agent.body, expectedSession, following).thenCompose(done -> { synchronized(this) { agent.settings = game.settings(agent.body); } return save(); });
        });
    }

    @Override public CompletableFuture<Void> send(String id, String text, String model, String effort) {
        return send(id,text,model,effort,new JsonObject());
    }
    @Override public CompletableFuture<Void> send(String id, String text, String model, String effort, JsonObject pointing) {
        return send(id, text, model, effort, pointing, "send");
    }
    @Override public CompletableFuture<Void> send(String id, String text, String model, String effort, JsonObject pointing, String delivery) {
        return send(id, text, model, effort, pointing, delivery, null);
    }
    @Override public CompletableFuture<Void> send(String id, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier) {
        return deliverInput(id,text,model,effort,pointing,delivery,serviceTier,null);
    }
    @Override public CompletableFuture<Void> send(String id, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier, List<String> images) {
        return deliverInput(id,text,model,effort,pointing,delivery,serviceTier,null,List.copyOf(images));
    }
    private CompletableFuture<Void> deliverInput(String id, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier, JsonObject origin) {
        return deliverInput(id,text,model,effort,pointing,delivery,serviceTier,origin,List.of());
    }
    private CompletableFuture<Void> deliverInput(String id, String text, String model, String effort, JsonObject pointing, String delivery, String serviceTier, JsonObject origin, List<String> images) {
        if (delivery == null || delivery.isBlank() || delivery.equals("send")) return startMessage(id, text, model, effort, pointing, serviceTier, origin, images);
        if (!delivery.equals("steer") && !delivery.equals("queue")) return failed("Unknown message delivery: " + delivery);
        if (text == null || text.isBlank() && images.isEmpty()) return failed("Message is empty.");
        var captured = pointing == null ? new JsonObject() : pointing.deepCopy();
        return ready.thenCompose(unused -> {
            synchronized (this) {
                Agent agent = require(id);
                String currentWorld = worldSession.get();
                if (currentWorld == null) return failed("Enter the agent's world before sending a message.");
                if (agent.stopping && agent.turnSession != null) return failed("Wait for the agent to stop before sending a message.");
                if (!agent.historyError.isBlank()) return failed("Conversation history is unavailable: " + agent.historyError);
                if (checkoutBusy(agent) || agent.updatingSettings || agent.recoveringBody != null && !agent.recoveringBody.isDone()) return failed("Wait for checkout operations or agent settings to finish saving before sending a message.");
                if (delivery.equals("queue")) {
                    if (agent.turnSession == null) return startMessage(id, text, model, effort, captured, serviceTier, origin, images);
                    if (!Objects.equals(agent.turnSession, currentWorld)) return failed("The agent's turn belongs to another world session.");
                    if (agent.queuedMessages.size() >= 20) return failed("The message queue is full (20 messages).");
                    if (origin == null) agent.holdMessages = false;
                    agent.queuedMessages.add(new QueuedMessage(UUID.randomUUID().toString(), text,
                        model == null || model.isBlank() ? agent.model : model,
                        effort == null || effort.isBlank() ? agent.effort : effort,
                        serviceTier == null || serviceTier.isBlank() ? agent.serviceTier : tier(serviceTier), captured, currentWorld, images));
                    return CompletableFuture.completedFuture(null);
                }
                if (!agent.activeTurn || agent.activeTurnId == null || agent.session == null
                        || !Objects.equals(agent.turnSession, currentWorld)) return failed("There is no active turn to steer. Send a new message instead.");
                if (model != null && !model.isBlank() && !model.equals(agent.model)
                    || effort != null && !effort.isBlank() && !effort.equals(agent.effort)
                    || serviceTier != null && !serviceTier.isBlank() && !tier(serviceTier).equals(agent.serviceTier))
                    return failed("Queue the message to change execution settings; steering uses the current turn's settings.");
                String context = !minecraftAccess(agent) ? text : "[Minecraft context supplied by Too Many Agents; captured pointing is a past observation, not live tracking]\n"
                    + "Pointing when chat opened: " + captured + "\n[User message]\n" + text;
                // Pin this turn. A completion race must never silently send the message to a different turn.
                if (!text(obj(connection(agent).description(),"capabilities"),"steerMode").equals("inject"))
                    return failed("This provider does not support steering an active turn. Queue the message instead.");
                if (origin == null) agent.holdMessages = false;
                String requestId = requested(agent, text, "steer", origin, images);
                String turnId = agent.activeTurnId;
                AgentHarness.Session session = agent.session;
                return agent.history.flush().thenCompose(saved -> {
                    synchronized (this) {
                        if (agent.stopping || !Objects.equals(turnId, agent.activeTurnId) || session != agent.session
                                || !Objects.equals(currentWorld, worldSession.get())) return AgentService.<Void>failed("The original turn is no longer available to steer.");
                        return connection(agent).steer(session, requestId, agent.assembler.nativeTurnId(turnId), AgentHarness.Input.withImages(context, images), options(agent));
                    }
                }).thenRun(() -> {
                    synchronized (this) {
                        resolveQuestionReply(agent, text);
                    }
                }).whenComplete((ignored,failure) -> {
                    if (failure == null) return;
                    synchronized(this) {
                        Throwable cause = failure;
                        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                        if (cause instanceof AgentHarness.RequestFailure rejected && rejected.rejected)
                            record(agent,object("type","client/turn/rejected","requestId",requestId,"reason","provider_rejected","message",message(failure)));
                        fail(agent,failure);
                    }
                });
            }
        });
    }

    @Override public CompletableFuture<Void> steerQueued(String id, String messageId) {
        return ready.thenCompose(unused -> {
            synchronized(this) {
                Agent agent=require(id);
                var queued=agent.queuedMessages.stream().filter(message->message.id().equals(messageId)).findFirst().orElse(null);
                if(queued==null)return failed("That message is no longer queued.");
                if(!agent.activeTurn || agent.activeTurnId==null || agent.session==null || agent.stopping
                    || !Objects.equals(queued.worldSession(),worldSession.get())
                    || !Objects.equals(agent.turnSession,worldSession.get()))return failed("There is no active turn to steer. The message remains queued.");
                if(!text(obj(connection(agent).description(),"capabilities"),"steerMode").equals("inject"))
                    return failed("Steering is unavailable. The message remains queued.");
                // Reserve under the same lock as queue draining. Never retry an uncertain steer.
                var delivery=deliverInput(id,queued.text(),null,null,queued.pointing(),"steer",null,null,queued.images());
                if(delivery.isCompletedExceptionally())return delivery;
                agent.queuedMessages.remove(queued);
                return delivery;
            }
        });
    }

    @Override public synchronized CompletableFuture<Void> cancelQueued(String id, String messageId) {
        Agent agent = require(id);
        if (!agent.queuedMessages.removeIf(message -> message.id().equals(messageId))) return failed("That message is no longer queued.");
        return CompletableFuture.completedFuture(null);
    }

    private void clearQueue(Agent agent, String reason) {
        if (agent.queuedMessages.isEmpty()) return;
        int count = agent.queuedMessages.size();
        agent.queuedMessages.clear();
        add(agent, "system", "Cancelled " + count + " queued message(s): " + reason);
    }

    /** Called under the service lock, so the next turn is reserved before another submission can race it. */
    private void sendNext(Agent agent) {
        if (agent.updatingSettings || agent.queuedMessages.isEmpty()) return;
        if (agent.backgroundRunning > 0 && !agent.permissions.equals(agent.sessionPermissions)) return;
        QueuedMessage next = agent.queuedMessages.getFirst();
        if (!Objects.equals(next.worldSession(), worldSession.get())) { clearQueue(agent, "world changed"); return; }
        agent.queuedMessages.removeFirst();
        startMessage(agent.id, next.text(), next.model(), next.effort(), next.pointing(), next.serviceTier(), null, next.images()).exceptionally(failure -> {
            synchronized (this) {
                add(agent, "system", "Queued message submission reported an error; it was not retried: " + next.text() + "\n" + message(failure));
            }
            return null;
        });
    }

    private CompletableFuture<Void> startMessage(String id, String text, String model, String effort, JsonObject pointing, String serviceTier, JsonObject origin, List<String> images) {
        var captured = pointing == null ? new JsonObject() : pointing.deepCopy();
        if (text == null || text.isBlank() && images.isEmpty()) return failed("Message is empty.");
        String expectedSession = worldSession.get();
        if (expectedSession == null) return failed("Enter the agent's world before sending a message.");
        return checkBody(id).thenCompose(recovered -> ready).thenCompose(unused -> {
            Agent agent;
            JsonObject permissions;
            String selectedTier, selectedModel;
            long submission;
            synchronized (this) {
                agent = require(id);
                if (!Objects.equals(expectedSession,worldSession.get()) || origin != null && (agent.holdMessages || agent.stopping))
                    return CompletableFuture.failedFuture(new AgentHarness.RequestFailure("Coordination delivery was held before submission.","staleTurn",true,new JsonObject()));
                selectedTier = serviceTier == null || serviceTier.isBlank() ? agent.serviceTier : tier(serviceTier);
                selectedModel = model == null || model.isBlank() ? agent.model : model;
                MinecraftStrategy.resolve(agent.strategy);
                if (!agent.historyError.isBlank()) return failed("Conversation history is unavailable: " + agent.historyError);
                if (checkoutBusy(agent) || agent.updatingSettings || agent.recoveringBody != null && !agent.recoveringBody.isDone()) return failed("Wait for checkout operations or agent settings to finish saving before sending a message.");
                if (agent.turnSession != null || !agent.requests.isEmpty()) return failed("The agent already has an active turn. Stop it before sending another message.");
                submission = ++agent.submission;
                permissions = agent.permissions.deepCopy();
                agent.turnPermissions = permissions;
                agent.status = "starting";
                if (origin == null) agent.holdMessages = false;
                agent.lastError = "";
                agent.stopping = false;
                agent.turnSession = expectedSession;
                agent.activeTurnId = null;
                agent.toolScope = game.newToolScope(expectedSession);
            }
            AgentHarness connection = connection(agent);
            AtomicBoolean dispatched = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<String> requestedId = new java.util.concurrent.atomic.AtomicReference<>();
            return selection(connection, selectedModel, effort, selectedTier, permissions).thenCompose(ignored -> {
                synchronized (this) {
                    if (agent.submission != submission || agent.stopping) return AgentService.<AgentHarness.Session>failed("turn_no_longer_active");
                    return ensureSession(agent);
                }
            }).thenCompose(session -> {
                if (!Objects.equals(expectedSession, worldSession.get())) return failed("World changed before the message could start.");
                // Also verify the saved body belongs to this world before spending a model turn.
                return game.call(agent.body, expectedSession, GameAccess.Operation.OBSERVE, object("radius", 1), MinecraftStrategy.resolve(agent.strategy).perception()).thenCompose(observation -> {
                    filterDiscovery(agent,observation);
                    synchronized (this) {
                        if (agent.submission != submission || agent.stopping) return AgentService.<Void>failed("turn_no_longer_active");
                        JsonObject answer = questionReply(text);
                        if (answer != null && agent.answeredQuestions.contains(text(answer, "questionId")))
                            return AgentService.<Void>failed("This question was already answered in the conversation.");
                        agent.model = model == null || model.isBlank() ? agent.model : model;
                        agent.effort = effort == null || effort.isBlank() ? agent.effort : effort;
                        agent.serviceTier = selectedTier;
                    }
                    String context = !minecraftAccess(agent) ? text : "[Minecraft context supplied by Too Many Agents; captured pointing is a past observation, not live tracking]\n"
                        + "Current settings: " + observation.get("settings") + "\n"
                        + "Pointing when chat opened: " + captured + "\n"
                        + (text(captured,"source").equals("minecraft_chat") ? "This message was addressed to you in Minecraft chat. Use the registered notification/chat tool for brief updates when finished or when you need help. Keep full details in this conversation.\n" : "")
                        + MinecraftStrategy.resolve(agent.strategy).turnInstructions() + "\n[User message]\n" + text;
                    return scriptConnection(agent,expectedSession).thenCompose(ready -> {
                        synchronized (this) {
                            if (agent.submission != submission || agent.stopping || !Objects.equals(agent.turnSession, expectedSession)) return failed("agent_stopping");
                            String requestId = requested(agent, text, "new-turn", origin, images);
                            requestedId.set(requestId);
                            return agent.history.flush().thenCompose(saved -> {
                                synchronized (this) {
                                    if (agent.submission != submission || agent.stopping || !Objects.equals(expectedSession, worldSession.get()))
                                        return AgentService.<String>failed("turn_no_longer_active");
                                    // Once delivery is possible, a restart must resume this session, never recreate it.
                                    agent.resumeRequired = true;
                                    return save().thenCompose(persisted -> {
                                        synchronized (this) {
                                            if (agent.submission != submission || agent.stopping || !Objects.equals(expectedSession,worldSession.get()))
                                                return AgentService.<String>failed("turn_no_longer_active");
                                            dispatched.set(true);
                                            return connection.send(session, requestId, AgentHarness.Input.withImages(context, images), options(agent));
                                        }
                                    });
                                }
                            }).thenApply(turn -> {
                                synchronized (this) { resolveQuestionReply(agent, text); }
                                return (Void) null;
                            });
                        }
                    });
                });
            }).thenCompose(ignored -> agent.history.flush()).thenCompose(ignored -> save()).whenComplete((ignored, failure) -> {
                if (failure != null) synchronized (this) {
                    if (agent.submission != submission) return;
                    clearQueue(agent, "message could not start");
                    Throwable cause = failure;
                    while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                    boolean rejected = cause instanceof AgentHarness.RequestFailure known && known.rejected;
                    if (!dispatched.get() || rejected) {
                        closeTools(agent, "turn_not_started"); agent.turnSession = null;
                        if (requestedId.get() != null) record(agent,object("type","client/turn/rejected","requestId",requestedId.get(),"reason",rejected ? "provider_rejected" : "not_dispatched","message",message(failure)));
                    }
                    fail(agent, failure);
                }
            });
        });
    }

    @Override public CompletableFuture<Void> interrupt(String id) {
        synchronized (this) {
            Agent agent = require(id);
            agent.holdMessages = true;
            if (agent.activeTurnId != null) agent.suppressedNotices.add(agent.activeTurnId);
            save();
            clearQueue(agent, "agent stopped");
            agent.stopping = true;
            closeTools(agent, "agent_stopped");
            scriptTokens.values().removeIf(agentId -> agentId.equals(id));
            // Record cancellation now; it runs before world actions can advance after unpause.
            game.requestActionStop(agent.body, worldSession.get());
            if (agent.turnSession == null) {
                if (agent.backgroundRunning == 0 || agent.session == null) return CompletableFuture.completedFuture(null);
                agent.status = "stopping";
                AgentHarness.Session session = agent.session;
                return connection(agent).release(session).thenRun(() -> { synchronized(this) { agent.session = null; agent.status = "idle"; } });
            }
            agent.status = "stopping";
            // Interrupt the harness independently of Minecraft's paused server thread.
            // If turn/start is still in flight, turn/started sends the interrupt instead.
            if (agent.session == null) return CompletableFuture.completedFuture(null);
            return connection(agent).interrupt(agent.session);
        }
    }

    private static void closeTools(Agent agent, String reason) {
        var scope = agent.toolScope;
        agent.toolScope = null;
        if (scope != null) scope.close(reason);
    }

    synchronized void worldClosed(String session) {
        for (var agent : agents.values()) {
            clearQueue(agent, "world closed");
            if (!Objects.equals(agent.turnSession, session)) continue;
            if (agent.activeTurnId != null) agent.suppressedNotices.add(agent.activeTurnId);
            agent.stopping = true;
            closeTools(agent, "world_closed");
            scriptTokens.values().removeIf(id -> id.equals(agent.id));
            if (agent.session != null && connections.containsKey(agent.providerId)) {
                agent.status = "stopping";
                connection(agent).interrupt(agent.session).exceptionally(failure -> { fail(agent, failure); return null; });
            }
        }
        save();
    }

    @Override public CompletableFuture<Void> archiveConversation(String id, boolean archived) {
        return archived ? archive(id) : restore(id);
    }

    /** Archived chats live in ~/.too-many-agents/archive; the agent record stays so it can be restored. */
    private Path historyDirectory(Agent agent) {
        return agent.conversationArchived ? ManagedStorage.root().resolve("archive") : storage.getParent().resolve("conversations");
    }

    /** One archive, like Codex and Claude Code: the body leaves, the station frees and the chat is kept. */
    private CompletableFuture<Void> archive(String id) {
        Agent existing;
        synchronized (this) { existing = agents.get(id); }
        if (existing == null) return failed("Unknown agent: " + id);
        // An agent whose body is already gone (e.g. an interrupted archive) only needs the rest.
        var removal = existing.lifecycle.equals("removed") ? CompletableFuture.<Void>completedFuture(null) : remove(id, false);
        return removal.thenCompose(removed -> {
            Agent agent = existing;
            // Codex keeps its own archive; Claude has none. Ours is what counts, so a failed provider call is fine.
            CompletableFuture<Void> provider = CompletableFuture.completedFuture(null);
            if (!agent.threadArchived && obj(connection(agent).description(),"capabilities").get("supportsThreadArchive").getAsBoolean()) {
                var session = new AgentHarness.Session(agent.id, agent.providerSessionId, "", Path.of(directory(agent)), agent.model, agent.effort, agent.serviceTier, true);
                provider = connection(agent).archive(session).handle((done, failure) -> { if (failure == null) synchronized (this) { agent.threadArchived = true; } return null; });
            }
            return provider.thenCompose(done -> moveHistory(agent, true)).thenCompose(moved -> {
                synchronized (this) { agent.lifecycle = "archived"; agent.status = "archived"; }
                return save();
            });
        });
    }

    /** Bring an archived agent back in this world: its chat returns and its body respawns near the player. */
    private CompletableFuture<Void> restore(String id) {
        return ready.thenCompose(unused -> {
            Agent agent;
            synchronized (this) {
                agent = agents.get(id);
                if (agent == null) return failed("Unknown agent: " + id);
                if (!agent.conversationArchived) return CompletableFuture.completedFuture(null);
                if (!game.belongsToCurrentWorld(agent.body)) return failed("Open this agent's world to restore it.");
            }
            return moveHistory(agent, false).thenCompose(moved -> {
                synchronized (this) {
                    agent.lifecycle = "active"; agent.status = "idle"; agent.stopping = false;
                    agent.threadArchived = false; agent.removalError = "";
                    agent.bodyRemoved = false; agent.bodyLost = true;
                }
                return save();
            }).thenCompose(saved -> checkBody(id));
        });
    }

    /** Close the history, move this agent's chat files between conversations/ and the archive, and reopen it there. */
    private CompletableFuture<Void> moveHistory(Agent agent, boolean archived) {
        Path from = historyDirectory(agent);
        return agent.history.closeAsync().thenCompose(closed -> CompletableFuture.runAsync(() -> {
            synchronized (this) { agent.conversationArchived = archived; }
            Path to = historyDirectory(agent);
            try {
                Files.createDirectories(to);
                if (Files.isDirectory(from)) try (var files = Files.list(from)) {
                    for (Path path : files.filter(path -> path.getFileName().toString().startsWith(agent.id + ".")).toList())
                        Files.move(path, to.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failure) { throw new CompletionException(failure); }
        }, disk)).thenCompose(moved -> {
            synchronized (this) { agent.history = new ConversationHistory(historyDirectory(agent), agent.id); }
            return agent.history.load();
        });
    }

    /** Retain only archived logs (tier 2), or delete this agent's local records (tier 3). */
    @Override public CompletableFuture<String> retire(String id, boolean deleteHistory) {
        return remove(id, false).thenCompose(removed -> {
            Agent agent;
            synchronized (this) {
                agent = agents.get(id);
                if (agent == null) return CompletableFuture.completedFuture("Agent already removed.");
                if (agent.removalInProgress) return failed("Removal is already in progress.");
                agent.removalInProgress = true;
            }
            CompletableFuture<String> provider = CompletableFuture.completedFuture("");
            if (deleteHistory && !agent.threadArchived && obj(connection(agent).description(),"capabilities").get("supportsThreadArchive").getAsBoolean()) {
                var session = new AgentHarness.Session(agent.id, agent.providerSessionId, "", Path.of(directory(agent)), agent.model, agent.effort, agent.serviceTier, true);
                try {
                    provider = connection(agent).archive(session).handle((done, failure) -> failure == null ? "" : "Local records removed. Provider archival was not confirmed: " + message(failure));
                } catch (RuntimeException failure) {
                    provider = CompletableFuture.completedFuture("Local records removed. Provider archival was not confirmed: " + message(failure));
                }
            }
            return provider.thenCompose(note -> agent.history.closeAsync().thenCompose(closed -> CompletableFuture.runAsync(() -> {
                try { retireFiles(agent, deleteHistory); }
                catch (IOException failure) { throw new CompletionException(failure); }
            }, disk)).thenCompose(cleaned -> {
                synchronized (this) {
                    agents.remove(id, agent);
                    scriptTokens.values().removeIf(value -> value.equals(id));
                    for (Agent other : agents.values()) {
                        if (other.parentId.equals(id)) other.parentId = "";
                        other.mailbox.removeIf(mail -> text(mail,"senderId").equals(id));
                    }
                    return save().whenComplete((saved, failure) -> {
                        if (failure != null) synchronized (this) { agents.put(id, agent); }
                    });
                }
            }).thenApply(saved -> note)).whenComplete((done, failure) -> {
                synchronized (this) { agent.removalInProgress = false; }
            });
        });
    }

    private void retireFiles(Agent agent, boolean deleteHistory) throws IOException {
        Path directory = storage.getParent().resolve("conversations");
        Path archive = directory.resolve("archive");
        if (!deleteHistory) {
            Files.createDirectories(archive);
            Files.writeString(archive.resolve(agent.id + ".json"), JSON.toJson(object("id",agent.id,"name",agent.name,"providerId",agent.providerId,"providerSessionId",agent.providerSessionId)));
        }
        // Only this conversation's owned logs and interrupted-write recovery files.
        for (Path folder : deleteHistory ? List.of(directory, archive) : List.of(directory)) {
            if (!Files.isDirectory(folder)) continue;
            try (var files = Files.list(folder)) {
                for (Path path : files.filter(path -> path.getFileName().toString().startsWith(agent.id + ".")).toList()) {
                    if (deleteHistory) {
                        if (path.getFileName().toString().endsWith(".events.jsonl")) {
                            try (var lines = Files.lines(path)) {
                                for (var iterator = lines.iterator(); iterator.hasNext();) {
                                    try { deleteImageCopies(JsonParser.parseString(iterator.next())); }
                                    catch (com.google.gson.JsonParseException ignored) { /* Still delete a damaged log. */ }
                                }
                            }
                        }
                        Files.deleteIfExists(path);
                    }
                    else Files.move(path, archive.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path credentials = descriptor(agent).getParent();
        if (Files.isDirectory(credentials)) try (var files = Files.walk(credentials)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static void deleteImageCopies(JsonElement value) throws IOException {
        if (value.isJsonArray()) {
            for (var entry : value.getAsJsonArray()) deleteImageCopies(entry);
        } else if (value.isJsonObject()) {
            var item = value.getAsJsonObject();
            if (text(item,"type").equals("localImage") && !text(item,"path").isBlank()) {
                Path path;
                try { path = Path.of(text(item,"path")).toAbsolutePath().normalize(); }
                catch (java.nio.file.InvalidPathException invalid) { return; }
                Path cache = Path.of(System.getProperty("java.io.tmpdir"),"too-many-agents-images").toAbsolutePath().normalize();
                if (cache.equals(path.getParent()) && path.getFileName().toString().startsWith("image-")) Files.deleteIfExists(path);
            }
            for (var entry : item.entrySet()) deleteImageCopies(entry.getValue());
        }
    }

    @Override public CompletableFuture<Void> remove(String id, boolean archiveThread) {
        String expectedSession = worldSession.get();
        return ready.thenCompose(unused -> {
            Agent agent;
            synchronized (this) {
                agent = agents.get(id);
                if (agent == null) return failed("Unknown agent: " + id);
                if (checkoutBusy(agent) || agent.updatingSettings || agent.recoveringBody != null && !agent.recoveringBody.isDone()) return failed("Wait for checkout operations or agent settings to finish saving before removing the agent.");
                if (archiveThread && !obj(connection(agent).description(),"capabilities").get("supportsThreadArchive").getAsBoolean()) return failed("This provider does not support native archival.");
                if (agent.removalInProgress) return failed("Removal is already in progress.");
                if (agent.turnSession != null || agent.activeTurn || agent.backgroundRunning > 0 || !agent.requests.isEmpty()) return failed("Stop the agent's active work before removing or archiving it.");
                if (agent.opening != null && !agent.opening.isDone()) return failed("The conversation is still opening. Wait, then retry removal.");
                agent.archiveRequested = archiveThread;
                agent.removalInProgress = true;
                agent.stopping = true;
                agent.removalError = "";
                agent.lifecycle = agent.bodyRemoved ? (agent.archiveRequested ? "archiving" : "removed") : "removing";
                agent.status = agent.lifecycle;
                agent.scriptSession = null;
                scriptTokens.values().removeIf(agentId -> agentId.equals(id));
            }
            // Archive before body cleanup so a missing body cannot block it.
            var work = save().thenCompose(saved -> {
                if (!archiveThread || agent.threadArchived) return CompletableFuture.<Void>completedFuture(null);
                var session = agent.session != null ? agent.session : new AgentHarness.Session(agent.id, agent.providerSessionId, "", Path.of(directory(agent)), agent.model, agent.effort, agent.serviceTier, true);
                return connection(agent).archive(session).thenCompose(archived -> {
                    synchronized (this) {
                        agent.threadArchived = true;
                        agent.conversationArchived = true;
                        agent.session = null;
                    }
                    return save();
                });
            }).thenCompose(saved -> {
                if (agent.bodyRemoved && !game.belongsToCurrentWorld(agent.body)) return CompletableFuture.<Void>completedFuture(null);
                return game.removeAgent(agent.body, expectedSession, agent.id, agent.bodyRemoved || agent.bodyLost).thenCompose(removed -> {
                    synchronized (this) {
                        agent.bodyRemoved = true;
                        agent.bodyLost = false;
                        agent.settings.addProperty("following", false);
                    }
                    return save();
                }).thenCompose(bodySaved -> releaseStation(agent.id, expectedSession));
            }).thenCompose(removed -> {
                var released = agent.session == null ? CompletableFuture.<Void>completedFuture(null) : connection(agent).release(agent.session);
                return released.thenCompose(releasedSession -> {
                    synchronized (this) {
                        agent.session = null;
                        agent.lifecycle = archiveThread && agent.threadArchived ? "archived" : "removed";
                        agent.status = agent.lifecycle;
                        agent.conversationArchived = archiveThread && agent.threadArchived;
                    }
                    return save();
                });
            }).thenRunAsync(() -> {
                // Revocation is already effective in memory and durable lifecycle gates future access.
                try { Files.deleteIfExists(descriptor(agent)); }
                catch (IOException failure) { synchronized (this) { add(agent, "system", "Credential revoked; could not delete its stale connection file: " + failure.getMessage()); } }
            }, disk);
            return work.exceptionallyCompose(failure -> {
                synchronized (this) {
                    agent.lifecycle = agent.archiveRequested && !agent.threadArchived ? "archive_failed" : "removal_failed";
                    agent.status = agent.lifecycle;
                    agent.removalError = (agent.threadArchived ? "Provider conversation archived. " : agent.archiveRequested ? "Provider archival is incomplete or unconfirmed. " : "The provider conversation was kept. ")
                        + (agent.bodyRemoved ? "Body already removed. " : "Body cleanup is pending; a missing body may be dead or unloaded. ")
                        + message(failure) + " Retry through Manage to finish cleanup.";
                    add(agent, "system", agent.removalError);
                }
                return save().handle((saved, saveFailure) -> {
                    // Archival succeeded; retain the body warning for an explicit cleanup retry.
                    if (saveFailure == null && archiveThread && agent.threadArchived && !agent.bodyRemoved) return null;
                    String detail = agent.removalError;
                    if (saveFailure != null) detail += " The failure state also could not be saved: " + message(saveFailure);
                    throw new CompletionException(new IllegalStateException(detail));
                });
            }).whenComplete((done, failure) -> { synchronized (this) { agent.removalInProgress = false; } });
        });
    }

    /** Frees the station an agent holds in the current world, if any. A body that is only missing or unloaded keeps it. */
    private CompletableFuture<Void> releaseStation(String agentId, String expectedSession) {
        boolean held = array(game.worldInfo(),"stations").asList().stream().anyMatch(station -> text(station.getAsJsonObject(),"agentId").equals(agentId));
        return held ? game.worldCommand(object("operation","station-release","agentId",agentId),expectedSession).thenApply(result -> null)
            : CompletableFuture.completedFuture(null);
    }

    @Override public CompletableFuture<Void> respond(String id, String requestId, String answer) {
        Agent agent;
        Pending pending;
        JsonObject response;
        synchronized (this) {
            agent = require(id);
            if (agent.asyncQuestions.containsKey(requestId)) return answerAsyncQuestion(agent, requestId, answer);
            pending = agent.requests.get(requestId);
            if (pending == null) return failed("This request is no longer pending.");
            if (answer == null || answer.isBlank()) return failed("Enter an answer.");
            if (agent.responsesInFlight.contains(pending.rpcId)) return failed("This response is already being sent.");
            if (pending.kind.equals("user_question")) {
                JsonObject typed = object("selected", List.of(), "freeText", answer);
                for (var entry : array(pending.params,"questions")) {
                    JsonObject question = entry.getAsJsonObject();
                    if (!text(question,"id").equals(pending.questionId)) continue;
                    for (var option : array(question,"options")) {
                        JsonObject choice = option.getAsJsonObject();
                        if (answer.equals(text(choice,"label"))) typed = object("selected",List.of(text(choice,"value")));
                    }
                }
                pending.answers.add(pending.questionId, typed);
                boolean remaining = agent.requests.values().stream().anyMatch(p -> p.rpcId.equals(pending.rpcId) && !p.answers.has(p.questionId));
                if (remaining) return CompletableFuture.completedFuture(null);
                response = object("kind", "user_question", "answers", pending.answers.deepCopy());
            } else {
                String label = approvalAnswer(pending, answer);
                if (label == null) return failed(pending.responses.isEmpty()
                    ? "This request has no supported choices. Stop the turn."
                    : "Choose one of the offered options: " + String.join(", ", pending.responses.keySet()) + ".");
                response = pending.responses.get(label).deepCopy();
            }
            interactionRecord(agent,pending.rpcId,pending.params,"resolving",response);
            agent.responsesInFlight.add(pending.rpcId);
        }
        return agent.history.flush().thenCompose(saved -> {
            synchronized (this) {
                if (agent.stopping || !agent.requests.values().stream().anyMatch(p -> p.rpcId.equals(pending.rpcId)))
                    return AgentService.<Void>failed("This request is no longer pending.");
                return connection(agent).respond(pending.rpcId, response);
            }
        }).whenComplete((unused, failure) -> {
            synchronized (this) {
                agent.responsesInFlight.remove(pending.rpcId);
                if (failure == null) {
                    if (agent.requests.values().stream().anyMatch(p -> p.rpcId.equals(pending.rpcId)))
                        interactionRecord(agent,pending.rpcId,pending.params,"resolved",response);
                    agent.requests.values().removeIf(p -> p.rpcId.equals(pending.rpcId));
                    if (agent.requests.isEmpty() && agent.turnSession != null) agent.status = "running";
                } else if (agent.requests.values().stream().anyMatch(p -> p.rpcId.equals(pending.rpcId))) {
                    agent.status = "waiting";
                    add(agent, "system", "Could not send the response: " + message(failure) + ". The request remains pending; retry when connected.");
                }
            }
        }).thenCompose(unused -> agent.history.flush());
    }

    /** Async questions are conversation messages, not outstanding JSON-RPC requests. */
    private CompletableFuture<Void> answerAsyncQuestion(Agent agent, String questionId, String answer) {
        if (answer == null || answer.isBlank()) return failed("Enter an answer.");
        if (agent.responsesInFlight.contains(questionId)) return failed("This answer is already being sent.");
        if (!game.belongsToCurrentWorld(agent.body)) return failed("Enter the agent's world before answering.");
        if (agent.stopping && agent.turnSession != null) return failed("Wait for the agent to stop before answering.");
        if (agent.turnSession != null && (!agent.activeTurn || agent.activeTurnId == null))
            return failed("The agent is starting a turn. Please send your answer again once it is running.");
        JsonObject question = agent.asyncQuestions.get(questionId);
        String reply = QUESTION_REPLY + JSON.toJson(object("questionId", questionId,
            "question", text(question, "title"), "answer", answer));
        agent.responsesInFlight.add(questionId);
        String delivery = agent.turnSession == null ? "send" : "steer";
        return send(agent.id, reply, agent.model, agent.effort, new JsonObject(), delivery)
            .thenCompose(unused -> save()).whenComplete((unused, failure) -> {
            synchronized (this) { agent.responsesInFlight.remove(questionId); }
        });
    }

    private static String approvalAnswer(Pending pending, String answer) {
        String value = answer.strip();
        for (String label : pending.responses.keySet()) if (label.equalsIgnoreCase(value)) return label;
        if (value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("approve")) {
            if (pending.responses.containsKey("Approve once")) return "Approve once";
            if (pending.responses.containsKey("Allow this turn")) return "Allow this turn";
        }
        if (value.equalsIgnoreCase("no")) {
            if (pending.responses.containsKey("Deny")) return "Deny";
            if (pending.responses.containsKey("Cancel")) return "Cancel";
        }
        return null;
    }

    /** Explicit bridge calls pin the current world session at submission. */
    CompletableFuture<JsonObject> call(String id, String tool, JsonObject arguments) {
        String expectedSession = worldSession.get();
        return ready.thenCompose(unused -> {
            Agent agent;
            synchronized (this) { agent = require(id); return MinecraftStrategy.resolve(agent.strategy).call(game, agent.body, expectedSession, null, tool, arguments); }
        });
    }

    private static String color(Agent agent) {
        String color = text(agent.settings,"color");
        return AgentColor.valid(color) ? color : AgentColor.forId(agent.id);
    }

    private static String communication(Agent agent) {
        String value = text(agent.settings,"communication");
        return value.isBlank() ? "project" : value;
    }

    private boolean descendsFrom(Agent child, Agent parent) {
        for (Agent next = agents.get(child.parentId); next != null; next = agents.get(next.parentId)) {
            if (next == parent) return true;
        }
        return false;
    }

    private boolean permitsContact(Agent from, Agent to) {
        return switch (communication(from)) {
            case "any" -> true;
            case "project" -> from.projectId.equals(to.projectId);
            case "children" -> descendsFrom(to, from) || descendsFrom(from, to);
            default -> false;
        };
    }

    private boolean canContact(Agent from, Agent to) {
        return from != null && to != null && permitsContact(from,to) && permitsContact(to,from);
    }

    private void requireContact(Agent from, Agent to) {
        if (from == to) return;
        if (from.updatingSettings || to.updatingSettings) throw new IllegalStateException("Wait for communication settings to finish saving.");
        if (!game.belongsToCurrentWorld(to.body)) throw new IllegalArgumentException("The agent is unavailable in this session.");
        if (!permitsContact(from,to) || !permitsContact(to,from)) throw new IllegalArgumentException("Agent communication is disabled for this pair by their agent settings.");
    }

    private Agent coordinationTarget(String reference) {
        if (agents.containsKey(reference)) return require(reference);
        var matches = agents.values().stream().filter(a -> active(a) && a.body != null && game.belongsToCurrentWorld(a.body)
            && (a.body.entityUuid().equals(reference) || a.name.equals(reference))).toList();
        if (matches.size() == 1) return require(matches.getFirst().id);
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous agent name: " + reference + ". Use an agent ID or body UUID.");
        throw new IllegalArgumentException("Unknown agent: " + reference + ". Use an agent ID, body UUID, or unique name in this world.");
    }

    private CompletableFuture<JsonObject> agentTool(Agent caller, String tool, JsonObject args) {
        var strategy = MinecraftStrategy.resolve(caller.strategy);
        var definition = strategy.tools().stream().filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        SharedModel.validateJsonSchema(definition.inputSchema(), args);
        if (caller.stopping || caller.toolScope == null || !Objects.equals(caller.turnSession,worldSession.get())) return failed("tool_turn_no_longer_active");
        return switch (tool) {
            case "agent_catalog" -> catalog(text(args,"provider").isBlank() ? caller.providerId : text(args,"provider")).thenApply(result -> {
                if (minecraftAccess(caller)) return result;
                return object("models",result.get("models"),"provider",result.get("provider"),"providers",result.get("providers"));
            });
            case "agent_spawn" -> spawnChild(caller,args);
            case "agent_read" -> {
                Agent target = text(args,"agentId").isBlank() ? caller : coordinationTarget(text(args,"agentId"));
                requireContact(caller,target);
                yield CompletableFuture.completedFuture(agentView(caller, target));
            }
            case "agent_message" -> {
                Agent recipient = coordinationTarget(text(args,"agentId"));
                requireContact(caller,recipient);
                if (recipient == caller) yield failed("Send messages to another agent; continue your own task directly.");
                if (!game.belongsToCurrentWorld(recipient.body)) yield failed("The receiving agent is unavailable in this session.");
                if (recipient.mailbox.stream().filter(m -> text(m,"state").equals("pending")).count() >= 20) yield failed("The receiving agent has 20 pending messages.");
                var message = mail(UUID.randomUUID().toString(),caller.id,"message",text(args,"message"),text(args,"delivery"));
                String summary = text(args,"summary").strip();
                if (args.has("summary") && (summary.isBlank() || summary.length() > 120 || summary.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '§' || c == 0x2028 || c == 0x2029)))
                    yield failed("Summary must be a single plain-text line of 1–120 characters.");
                message.addProperty("summary", summary.isBlank() ? "Sent a message." : summary);
                recipient.mailbox.add(message);
                yield save().thenApply(done -> object("agentId",recipient.id,"messageId",text(message,"id"),"delivery","queued","held",recipient.holdMessages));
            }
            case "agent_wait" -> waitAgents(caller,args);
            case "agent_stations" -> {
                var stations = new JsonArray();
                for (var item : array(game.worldInfo(),"stations")) {
                    var station = item.getAsJsonObject();
                    if (!text(station,"projectId").equals(caller.projectId)) continue;
                    var occupant = agents.get(text(station,"agentId"));
                    var row = object("id",text(station,"id"),"label",text(station,"label"),"occupant",text(station,"agentId").isBlank() ? null
                        : object("agentId",text(station,"agentId"),"name",occupant == null ? "" : occupant.name));
                    if (minecraftAccess(caller)) row.add("box",object("dimension",station.get("dimension"),"min",station.get("min"),"max",station.get("max")));
                    stations.add(row);
                }
                yield CompletableFuture.completedFuture(object("projectId",caller.projectId,"stations",stations));
            }
            case "agent_archive" -> {
                Agent child = coordinationTarget(text(args,"agentId"));
                if (!child.parentId.equals(caller.id)) yield failed("Only your own children can be archived.");
                requireContact(caller,child);
                var kept = object("agentId",child.id,"name",child.name,"directory",directory(child),"checkout",projects.checkout(child.projectId,child.checkoutId));
                // The same archive as the chat button: body removed, station freed, chat kept; checkout, branch and files stay.
                yield archive(child.id).thenApply(done -> { kept.addProperty("archived",true); return kept; });
            }
            case "agent_stop" -> {
                Agent target = coordinationTarget(text(args,"agentId"));
                requireContact(caller,target);
                if (!game.belongsToCurrentWorld(target.body)) yield failed("The agent is unavailable in this session.");
                yield interrupt(target.id).thenApply(done -> object("agentId",target.id,"stopped",true,"childrenStopped",false));
            }
            default -> failed("Unknown agent tool: " + tool);
        };
    }

    private CompletableFuture<JsonObject> spawnChild(Agent parent, JsonObject args) {
        if (parent.updatingSettings) return failed("Wait for communication settings to finish saving.");
        if (communication(parent).equals("none")) return failed("Agent coordination is disabled for this agent.");
        int depth = 1;
        for (Agent ancestor = parent; ancestor != null && !ancestor.parentId.isBlank(); ancestor = agents.get(ancestor.parentId)) {
            if (++depth >= 4) return failed("Maximum child-agent depth is four, including the root.");
        }
        String profileName = text(args,"profile").strip();
        var profile = profileName.isBlank() ? new JsonObject() : profiles.get(profileName);
        if (profile == null) return failed("Unknown profile: " + profileName + ". Saved profiles: " + String.join(", ", profiles.keySet()) + ".");
        String provider = text(args,"provider");
        if (provider.isBlank() && !profileName.isBlank()) provider = providerId(profile);
        if (provider.isBlank()) return failed("Choose a provider or a profile.");
        if (!profileName.isBlank() && !provider.equals(providerId(profile))) return failed("Profile " + profileName + " is for provider " + providerId(profile) + ".");
        String stationId = text(args,"station");
        if (!stationId.isBlank()) {
            var station = array(game.worldInfo(),"stations").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> text(row,"id").equals(stationId) && text(row,"projectId").equals(parent.projectId)).findFirst().orElse(null);
            if (station == null) return failed("Unknown station in your project: " + stationId + ".");
            if (!text(station,"agentId").isBlank()) return failed("Station " + text(station,"label") + " is occupied.");
        }
        var spec = object("providerId",provider,"projectId",parent.projectId,"name",text(args,"name"),"checkoutId",parent.checkoutId,
            "mode",text(parent.settings,"mode"),"cheats",parent.settings.get("cheats"),"following",false,
            "minecraftAccess",minecraftAccess(parent),"nativeSubagentsEnabled",parent.nativeSubagentsEnabled,"communication",communication(parent),"initialTask",text(args,"task"),"title",text(args,"title"));
        // A profile replaces inherited presentation settings. It can turn capabilities off, never on; permissions and communication stay the parent's.
        for (String key : List.of("body","mode","followReturn","behaviors")) if (profile.has(key)) spec.add(key,profile.get(key).deepCopy());
        for (String key : List.of("cheats","nativeSubagentsEnabled","minecraftAccess")) if (profile.has(key) && !profile.get(key).getAsBoolean()) spec.addProperty(key,false);
        if (!text(args,"body").isBlank()) spec.addProperty("body",text(args,"body"));
        if (!text(args,"directory").isBlank()) { spec.remove("checkoutId"); spec.addProperty("directory",text(args,"directory")); }
        if (args.has("useWorktree")) spec.add("useWorktree",args.get("useWorktree"));
        if (args.has("baseRef")) spec.add("baseRef",args.get("baseRef"));
        var permissions = options(parent).permissions().deepCopy();
        permissions.entrySet().forEach(e -> spec.add(e.getKey(),e.getValue()));
        var scope = parent.toolScope;
        String expectedWorld = parent.turnSession, childProvider = provider;
        return connection(provider).connect().thenCompose(models -> {
            synchronized (this) {
                if (scope != parent.toolScope || parent.stopping || !Objects.equals(expectedWorld,worldSession.get())) return failed("tool_turn_no_longer_active");
                if (parent.updatingSettings || communication(parent).equals("none")) return failed("Agent coordination is disabled or changing for this agent.");
                spec.addProperty("communication", communication(parent));
                String selected = text(args,"model");
                if (selected.isBlank()) selected = text(profile,"model");
                if (selected.isBlank() && childProvider.equals(parent.providerId)) selected = parent.model;
                if (selected.isBlank()) selected = models.stream().findFirst().orElseThrow(() -> new IllegalStateException("Provider has no models.")).id();
                String modelId = selected;
                var model = models.stream().filter(m -> m.id().equals(modelId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Provider does not offer model " + modelId));
                boolean profileModel = modelId.equals(text(profile,"model"));
                String effort = text(args,"effort");
                if (effort.isBlank() && profileModel) effort = text(profile,"effort");
                if (effort.isBlank()) effort = childProvider.equals(parent.providerId) && modelId.equals(parent.model) ? parent.effort : model.defaultEffort();
                if (profileModel && profile.has("serviceTier")) spec.add("serviceTier",profile.get("serviceTier"));
                spec.addProperty("model",modelId); spec.addProperty("effort",effort);
                return spawn(spec,parent,scope,stationId).thenApply(id -> {
                    synchronized (this) {
                        var child = require(id);
                        var result = object("agentId",id,"parentId",parent.id,"provider",childProvider,"directory",directory(child),"projectId",child.projectId,"checkoutId",child.checkoutId,"checkout",projects.checkout(child.projectId,child.checkoutId),"permissions",permissions,"status","queued");
                        if (!profileName.isBlank()) result.addProperty("profile",profileName);
                        if (!stationId.isBlank()) result.addProperty("station",stationId);
                        return result;
                    }
                });
            }
        });
    }

    private JsonObject agentView(Agent caller, Agent agent) {
        JsonArray children = new JsonArray();
        for (var child : agents.values()) if (child.parentId.equals(agent.id) && canContact(caller,child)) children.add(object("agentId",child.id,"name",child.name,"provider",child.providerId,"status",child.status));
        var state = snapshot(agent.id);
        var transcript = agent.history.transcript(new JsonObject());
        var messages = new JsonArray();
        for (var entry : array(transcript,"entries")) if (entry.getAsJsonObject().has("role")) messages.add(entry);
        var result = object("agentId",agent.id,"name",agent.name,"parentId",agent.parentId,"provider",agent.providerId,"model",agent.model,
            "directory",directory(agent),"projectId",agent.projectId,"checkoutId",agent.checkoutId,"body",agent.body,"status",agent.status,"turnActive",agent.turnSession != null,"backgroundRunning",agent.backgroundRunning,"messagesHeld",agent.holdMessages,
            "result",resultText(agent,""),"messages",messages,"transcript",transcript,"requests",state.get("requests"),"children",children,"mailbox",agent.mailbox,"error",agent.lastError);
        if (!minecraftAccess(caller)) result.remove("body");
        result.addProperty("communication", communication(agent));
        result.add("checkout",projects.checkout(agent.projectId,agent.checkoutId));
        return result;
    }

    private CompletableFuture<JsonObject> waitAgents(Agent caller, JsonObject args) {
        List<Agent> targets = new ArrayList<>();
        for (var id : array(args,"agentIds")) {
            var target = coordinationTarget(id.getAsString());
            requireContact(caller,target);
            if (target == caller) return failed("An agent cannot wait for itself.");
            targets.add(target);
        }
        int seconds = args.has("timeoutSeconds") ? args.get("timeoutSeconds").getAsInt() : 20;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.clamp(seconds,0,30));
        var scope = caller.toolScope;
        var result = new CompletableFuture<JsonObject>();
        Runnable poll = new Runnable() {
            public void run() {
                synchronized (AgentService.this) {
                    if (shuttingDown || caller.toolScope != scope || caller.stopping) { result.completeExceptionally(new IllegalStateException("tool_turn_no_longer_active")); return; }
                    try { for (var target : targets) requireContact(caller,target); }
                    catch (RuntimeException denied) { result.completeExceptionally(denied); return; }
                    boolean changed = targets.stream().anyMatch(a -> !a.requests.isEmpty() || !a.asyncQuestions.isEmpty() || !a.lastError.isBlank()
                        || !a.provisioning && a.turnSession == null && a.backgroundRunning == 0 && a.mailbox.stream().noneMatch(m -> Set.of("pending","attempting").contains(text(m,"state"))));
                    if (changed || System.nanoTime() >= deadline) {
                        var states = new JsonArray(); targets.forEach(a -> states.add(agentView(caller, a)));
                        result.complete(object("timedOut",!changed,"agents",states));
                    } else coordination.schedule(this,250,TimeUnit.MILLISECONDS);
                }
            }
        };
        poll.run();
        return result;
    }

    private static JsonObject mail(String id, String sender, String kind, String message, String delivery) {
        return object("id",id,"senderId",sender,"kind",kind,"text",message,"delivery",delivery.isBlank() ? "auto" : delivery,"state","pending");
    }

    private String resultText(Agent agent, String turn) {
        String result = agent.history.lastReply(turn);
        return result.length() > 4000 ? result.substring(0,4000) + "\n[Read the child conversation for the rest.]" : result;
    }

    private void childNotice(Agent child, String source, String kind, String detail) {
        Agent parent = agents.get(child.parentId);
        if (parent == null || child.suppressedNotices.contains(source)) return;
        String id = child.id + ":" + source + ":" + kind;
        if (parent.mailbox.stream().anyMatch(m -> text(m,"id").equals(id))) return;
        parent.mailbox.add(mail(id,child.id,kind,"Child " + child.name + " (" + child.id + ") " + kind + ".\n" + detail,"auto"));
        child.history.flush().thenCompose(done -> save());
    }

    private void pumpMessages() {
        synchronized (this) {
            if (!ready.isDone() || ready.isCompletedExceptionally() || shuttingDown || closed || worldSession.get() == null || !storageError.isBlank()) return;
            for (Agent agent : List.copyOf(agents.values())) {
                if (agent.turnSession == null && !agent.holdMessages && active(agent)) agent.stopping = false;
                if (!active(agent) || agent.body == null || agent.provisioning || agent.delivering || agent.holdMessages || !agent.historyError.isBlank()
                        || agent.updatingSettings || agent.stopping || !agent.requests.isEmpty() || !agent.asyncQuestions.isEmpty()
                        || !game.belongsToCurrentWorld(agent.body)) continue;
                JsonObject mail = agent.mailbox.stream().filter(m -> text(m,"state").equals("pending")).findFirst().orElse(null);
                if (mail == null) continue;
                Agent sender = agents.get(text(mail,"senderId"));
                if (sender != null && sender.updatingSettings) continue;
                if (!canContact(sender,agent)) {
                    mail.addProperty("state","blocked");
                    mail.addProperty("error","Communication settings no longer allow this message.");
                    save();
                    continue;
                }
                boolean steer = agent.turnSession != null;
                if (steer && (!agent.activeTurn || text(mail,"delivery").equals("queue")
                    || !text(obj(connection(agent).description(),"capabilities"),"steerMode").equals("inject"))) continue;
                String expectedWorld = worldSession.get(), expectedTurn = agent.activeTurnId;
                agent.delivering = true;
                mail.addProperty("state","attempting");
                // Persist the claim before contacting a provider. An uncertain attempt is never replayed.
                save().thenCompose(done -> {
                    synchronized (this) {
                        if (agent.holdMessages || agent.stopping || agent.updatingSettings || sender.updatingSettings || !canContact(sender,agent) || !Objects.equals(expectedWorld,worldSession.get()) || !Objects.equals(expectedTurn,agent.activeTurnId)) {
                            mail.addProperty("state","pending"); return save();
                        }
                        String content = "[Agent coordination: " + text(mail,"kind") + "; sender " + text(mail,"senderId") + "; delivery " + text(mail,"id") + "]\n" + text(mail,"text");
                        return deliverInput(agent.id,content,agent.model,agent.effort,new JsonObject(),steer ? "steer" : "send",agent.serviceTier,mail)
                            .handle((sent,error) -> {
                                synchronized (this) {
                                    Throwable cause = error;
                                    while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                                    boolean rejected = cause instanceof AgentHarness.RequestFailure request && request.rejected;
                                    mail.addProperty("state",error == null ? "delivered" : rejected ? "pending" : "uncertain");
                                    if (error == null) {
                                        String summary = text(mail,"summary");
                                        if (summary.isBlank()) summary = switch (text(mail,"kind")) {
                                            case "task" -> "Started a task.";
                                            case "completed" -> "Finished a turn.";
                                            case "failed" -> "Reported a failure.";
                                            default -> "Sent an update.";
                                        };
                                        game.announceCommunication(sender.body, agent.body, expectedWorld, summary).exceptionally(ignored -> null);
                                    }
                                    if (error != null) { mail.addProperty("error",message(error)); agent.holdMessages = true; }
                                }
                                return null;
                            }).thenCompose(sent -> save());
                    }
                }).whenComplete((done,error) -> { synchronized (this) {
                    agent.delivering = false;
                    if (error != null) { agent.holdMessages = true; mail.addProperty("error",message(error)); }
                } });
            }
        }
    }

    private CompletableFuture<JsonObject> worldTool(String provider, String generation, String threadId, String turnId, String tool, JsonObject arguments) {
        Agent agent;
        CompletableFuture<JsonObject> dispatched;
        synchronized (this) {
            Connection owner = connections.get(provider);
            if (owner == null || !owner.generation().equals(generation)) return failed("harness_connection_changed");
            agent = agents.get(threadId);
            if (agent == null || !agent.providerId.equals(provider) || !active(agent) || agent.turnSession == null) return failed("No active turn is associated with this thread.");
            if (agent.stopping || agent.toolScope == null) return failed("agent_stopping");
            if (!Objects.equals(agent.activeTurnId, agent.assembler.canonicalTurnId(turnId))) return failed("tool_turn_no_longer_active");
            if (MinecraftStrategy.resolve(agent.strategy).isAgentTool(tool)) return agentTool(agent, tool, arguments);
            dispatched = MinecraftStrategy.resolve(agent.strategy).call(game, agent.body, agent.turnSession, agent.toolScope, tool, arguments);
        }
        return dispatched.thenApply(result -> {
            filterDiscovery(agent,result);
            if (MinecraftStrategy.resolve(agent.strategy).operation(tool) == GameAccess.Operation.OBSERVE) synchronized (this) {
                result.addProperty("directory", directory(agent));
                result.addProperty("model", agent.model);
                result.addProperty("effort", agent.effort);
                result.addProperty("agentId", agent.id);
            }
            return result;
        });
    }

    private synchronized JsonObject filterDiscovery(Agent caller, JsonObject result) {
        filterAgentMetadata(caller,result);
        return result;
    }
    private void filterAgentMetadata(Agent caller, JsonElement value) {
        if (value.isJsonArray()) { for (var item : value.getAsJsonArray()) filterAgentMetadata(caller,item); }
        else if (value.isJsonObject()) {
            var row = value.getAsJsonObject();
            if (row.has("agent") && row.get("agent").isJsonObject()) {
                var target = agents.get(text(row.getAsJsonObject("agent"),"id"));
                if (target == null) row.remove("agent");
                else {
                    // A visible body remains identifiable even when its conversation is private.
                    boolean allowed = target == caller || canContact(caller,target);
                    var metadata = allowed ? row.getAsJsonObject("agent") : object("id",target.id);
                    metadata.addProperty("communicationAllowed",allowed);
                    if (!allowed) metadata.addProperty("communicationBlockedBy","agent-settings");
                    row.add("agent",metadata);
                }
            }
            for (var item : row.entrySet()) filterAgentMetadata(caller,item.getValue());
        }
    }

    private synchronized CompletableFuture<AgentHarness.Session> ensureSession(Agent agent) {
        if (!active(agent)) return failed("agent_not_active: " + agent.lifecycle);
        try { MinecraftStrategy.resolve(agent.strategy); }
        catch (IllegalStateException unavailable) { return CompletableFuture.failedFuture(unavailable); }
        if (agent.opening != null) return agent.opening;
        var options = options(agent);
        if (agent.session != null && agent.sessionPermissions.equals(options.permissions())
                && options.directory().equals(agent.sessionDirectory) && options.additionalDirectories().equals(agent.sessionAdditionalDirectories)) return CompletableFuture.completedFuture(agent.session);
        if (agent.session != null && (agent.activeTurn || agent.backgroundRunning > 0 || !agent.requests.isEmpty()))
            return failed("Folder and approval settings will apply after the current turn and background work finish.");
        if (!obj(connection(agent).description(),"capabilities").get("sessionRestore").getAsBoolean()) return failed("This provider cannot restore a session.");
        AgentHarness connection = connection(agent);
        agent.historyRequested = true;
        boolean empty = !agent.resumeRequired;
        // Sandbox and bypass availability are fixed when a provider session opens.
        var release = agent.session == null ? CompletableFuture.<Void>completedFuture(null)
            : connection.release(agent.session).thenRun(() -> { synchronized(this) { agent.session = null; } });
        CompletableFuture<AgentHarness.Session> opening = release.thenCompose(unused ->
            empty ? connection.start(agent.id,options) : connection.resume(agent.id, agent.providerSessionId, options))
            .thenApply(session -> {
                synchronized (this) {
                    if (connection(agent) != connection || closed) throw new IllegalStateException("harness_connection_changed");
                    if (!active(agent)) throw new IllegalStateException("agent_not_active: " + agent.lifecycle);
                    agent.session = session;
                    agent.sessionPermissions = options.permissions().deepCopy();
                    agent.sessionDirectory = options.directory();
                    agent.sessionAdditionalDirectories = options.additionalDirectories();
                    agent.providerSessionId = session.providerSessionId();
                    agent.resumeRequired |= session.restorable();
                    if (empty) add(agent,"system","Opened a provider session for this empty conversation; no earlier input had been sent.");
                    agent.model = session.model(); agent.effort = session.effort(); agent.serviceTier = tier(session.serviceTier());
                    save();
                }
                return session;
            });
        agent.opening = opening;
        opening.whenComplete((session, failure) -> { synchronized (this) { if (agent.opening == opening) agent.opening = null; } });
        return opening;
    }

    private CompletableFuture<Void> selection(AgentHarness connection, String model, String effort, String serviceTier, JsonObject permissions) {
        return connection.connect().thenApply(models -> {
            if (model == null || model.isBlank()) return null;
            AgentHarness.Model selected = models.stream().filter(m -> m.id().equals(model)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Provider does not offer model " + model));
            if (text(permissions,"permissionMode").equals("auto") && Boolean.FALSE.equals(selected.supportsAutoMode()))
                throw new IllegalArgumentException(selected.label() + " does not support Approve for me. Choose Accept Edits or another model.");
            if (effort != null && !effort.isBlank() && !selected.efforts().contains(effort))
                throw new IllegalArgumentException("Model " + model + " does not support effort " + effort);
            if (selected.serviceTiers().stream().noneMatch(option -> option.id().equals(serviceTier)))
                throw new IllegalArgumentException("Model " + model + " does not offer speed tier " + serviceTier);
            return null;
        });
    }

    private synchronized void harnessEvent(String provider, String generation, AgentHarness.Event event) {
        Connection owner = connections.get(provider);
        if (closed || owner == null || !owner.generation().equals(generation)) return;
        if (event.kind().equals("connection/error")) {
            for (Agent agent : agents.values()) {
                if (!active(agent) || !agent.providerId.equals(provider)) continue;
                closeTools(agent,"harness_disconnected");
                if (agent.turnSession != null) game.requestActionStop(agent.body,agent.turnSession);
                agent.assembler.accept(object("kind","session.ended"));
                clearQueue(agent,"provider disconnected");
                closeRequests(agent,"interrupted");
                agent.session = null; agent.opening = null; agent.turnSession = null; agent.activeTurn = false; agent.activeTurnId = null;
                fail(agent,new IOException(event.text()));
            }
            connections.remove(provider,owner); owner.harness().close();
            return;
        }
        Agent agent = agents.get(event.threadId());
        if (agent == null || !agent.providerId.equals(provider) || !active(agent)) return;
        try {
            switch (event.kind()) {
                case "delta" -> {
                    agent.incomingNativeTurnId = event.turnId();
                    try { agent.assembler.accept(event.data()); }
                    finally { agent.incomingNativeTurnId = null; }
                }
                case "interaction/request" -> pending(agent,event);
                case "interaction/resolved" -> {
                    String id = text(event.data(),"id");
                    Pending request = agent.requests.values().stream().filter(p -> p.rpcId.equals(id)).findFirst().orElse(null);
                    if (request != null) {
                        String status = text(event.data(),"status");
                        if (status.isBlank()) status = "resolved";
                        if (!Set.of("resolved","interrupted").contains(status)) throw new IllegalArgumentException("Invalid interaction settlement status");
                        interactionRecord(agent, request.rpcId, request.params, status, event.data().has("answer") ? obj(event.data(),"answer") : null);
                        agent.requests.values().removeIf(p -> p.rpcId.equals(id));
                    }
                    if (agent.requests.isEmpty() && agent.activeTurn && !agent.stopping) agent.status = "running";
                }
                case "session/replaced" -> {
                    String nativeId = text(event.data(),"providerSessionId");
                    if (!nativeId.isBlank()) {
                        agent.providerSessionId = nativeId;
                        AgentHarness.Session old = agent.session;
                        if (old != null) agent.session = new AgentHarness.Session(old.threadId(),nativeId,old.connectionId(),old.cwd(),old.model(),old.effort(),old.serviceTier(),old.restorable());
                        save();
                    } else agent.session = null;
                    agent.assembler.accept(object("kind","session.ended"));
                    closeRequests(agent,"interrupted");
                    add(agent,"system", event.text() + (event.data().has("contextLost") && event.data().get("contextLost").getAsBoolean() ? " Previous provider context was lost." : ""));
                }
                case "session/disconnected" -> {
                    closeTools(agent,"harness_disconnected");
                    if (agent.turnSession != null) game.requestActionStop(agent.body,agent.turnSession);
                    agent.assembler.accept(object("kind","session.ended"));
                    closeRequests(agent,"interrupted"); clearQueue(agent,"provider disconnected");
                    agent.session = null; agent.opening = null; agent.turnSession = null; agent.activeTurn = false; agent.activeTurnId = null;
                    fail(agent,new IOException(event.text()));
                }
                case "recovery" -> { agent.recovery = event.data().deepCopy(); fail(agent,new IOException(event.text())); }
                default -> add(agent,"system","Unsupported connector event: " + event.kind());
            }
        } catch (RuntimeException invalid) {
            closeTools(agent,"invalid_provider_event");
            fail(agent,new IOException("Provider event rejected (" + event.kind() + "): " + message(invalid),invalid));
        }
    }

    private synchronized void sharedEvent(Agent agent, JsonObject event) {
        record(agent,event);
        int previousBackground = agent.backgroundRunning;
        JsonObject projectedState = obj(agent.history.stateSnapshot(),"state");
        agent.backgroundRunning = projectedState.has("backgroundRunning") ? projectedState.get("backgroundRunning").getAsInt() : 0;
        if (agent.turnSession == null && agent.backgroundRunning == 0 && agent.status.equals("background")) agent.status = "idle";
        String turn = text(obj(event,"scope"),"turnId");
        if (previousBackground > 0 && agent.backgroundRunning == 0 && agent.turnSession == null && !agent.stopping) {
            childNotice(agent, "background-" + text(obj(event,"item"),"id"), "completed", resultText(agent,""));
            if (!agent.holdMessages) sendNext(agent);
        }
        switch (text(event,"type")) {
            case "thread/name/updated" -> {
                String title = conciseTitle(text(event,"threadName"));
                if (!title.isBlank() && !agent.taskTitleSource.equals("set")) {
                    agent.taskTitle = title;
                    agent.taskTitleSource = "provider";
                    save();
                }
            }
            case "turn/started" -> {
                if (event.has("parentToolCallId")) return;
                if (agent.incomingNativeTurnId != null && !agent.incomingNativeTurnId.isBlank())
                    agent.assembler.bindNativeTurn(agent.incomingNativeTurnId,turn);
                agent.activeTurnId = turn; agent.activeTurn = true;
                agent.status = agent.stopping ? "stopping" : "running";
                if (agent.stopping && agent.session != null) connection(agent).interrupt(agent.session).exceptionally(error -> { fail(agent,error); return null; });
            }
            case "item/agentMessage/delta" -> { if (!text(event,"delta").isBlank()) agent.replyVersion++; }
            case "item/completed" -> {
                JsonObject item = obj(event,"item");
                if (text(item,"type").equals("agentMessage") && !text(item,"text").isBlank()) agent.replyVersion++;
            }
            case "turn/completed" -> {
                if (!Objects.equals(turn,agent.activeTurnId)) return;
                boolean finished = !agent.stopping && text(event,"status").equals("completed");
                if (!agent.stopping && !text(event,"status").equals("interrupted")) childNotice(agent, turn, text(event,"status"), (agent.backgroundRunning > 0 ? "Background work is still running; this output is not final.\n" : "") + resultText(agent, turn));
                agent.submission++;
                closeTools(agent,finished ? "turn_completed" : "turn_incomplete");
                if (!finished && agent.turnSession != null) game.requestActionStop(agent.body,agent.turnSession);
                agent.activeTurnId = null; agent.activeTurn = false; agent.turnSession = null;
                agent.status = agent.backgroundRunning > 0 ? "background" : "idle";
                closeRequests(agent,"interrupted");
                if (finished) { agent.lastError = ""; sendNext(agent); }
                else { clearQueue(agent,"turn did not finish successfully"); if (text(event,"status").equals("interrupted")) add(agent,"system","Stopped."); }
                save();
            }
            case "provider/error" -> {
                String category = text(obj(event,"errorInfo"),"category");
                String recovery = switch(category) {
                    case "unauthorized" -> "authRequired";
                    case "rate-limit" -> "rateLimited";
                    case "active-turn-not-steerable" -> "staleTurn";
                    case "connection-failed", "stream-disconnected" -> "restartRecommended";
                    default -> "";
                };
                if (!recovery.isBlank()) agent.recovery = object("kind",recovery,"knownRejected",false,"message",text(event,"message"));
                if (!event.has("willRetry") || !event.get("willRetry").getAsBoolean()) clearQueue(agent,"provider error");
                agent.lastError = text(event,"message");
                agent.errorVersion++;
            }
            default -> { }
        }
    }

    private void pending(Agent agent, AgentHarness.Event event) {
        JsonObject data = event.data().deepCopy(), payload = obj(data,"payload");
        String rpcId = text(data,"id"), kind = text(payload,"kind");
        JsonObject subject = obj(payload,"subject");
        if (!text(subject,"itemId").isBlank()) {
            String mapped = agent.assembler.canonicalItemId(text(subject,"itemId"));
            if (mapped != null) subject.addProperty("itemId",mapped);
        }
        interactionRecord(agent,rpcId,payload,"pending",null);
        if (kind.equals("user_question")) {
            JsonObject answers = new JsonObject();
            for (var value : array(payload,"questions")) {
                JsonObject question = value.getAsJsonObject();
                String questionId = text(question,"id"), id = rpcId + ":" + questionId;
                JsonArray options = new JsonArray();
                for (var option : array(question,"options")) options.add(text(option.getAsJsonObject(),"label"));
                JsonObject display = object("id",id,"kind","input","title",text(question,"prompt"),"options",options);
                if (text(data,"delivery").equals("async")) {
                    display.addProperty("async",true); display.add("payload",payload); display.addProperty("rpcId",rpcId);
                    if (!agent.answeredQuestions.contains(id)) agent.asyncQuestions.put(id,display);
                } else agent.requests.put(id,new Pending(rpcId,kind,payload,questionId,answers,display,Map.of()));
            }
        } else if (kind.equals("approval")) {
            Map<String,JsonObject> choices = new LinkedHashMap<>();
            for (var decision : array(payload,"availableDecisions")) {
                String id = decision.getAsString();
                String label = switch (id) { case "allow_once" -> "Approve once"; case "allow_for_session" -> "Approve for session"; case "deny" -> "Deny"; default -> id; };
                JsonObject response = object("kind","approval","decision",id);
                if (!id.equals("deny")) {
                    JsonElement grant = subject.get("permissions");
                    if ((grant == null || grant.isJsonNull()) && id.equals("allow_for_session")) grant = subject.get("sessionGrant");
                    if (grant != null) response.add("grantedPermissions",grant.deepCopy());
                }
                choices.put(label,response);
            }
            String title = switch (text(subject,"kind")) { case "command" -> "Allow this command?"; case "file_change" -> "Allow these file changes?"; case "plan" -> "Approve this plan?"; default -> "Allow this tool request?"; };
            String details = text(payload,"reason");
            JsonObject presentation = obj(subject,"presentation");
            String body = text(presentation,"detail");
            if (body.isBlank()) body = text(subject,"command");
            if (body.isBlank()) body = text(subject,"plan");
            if (body.isBlank()) body = JSON.toJson(subject);
            if (!body.isBlank()) details += (details.isBlank() ? "" : "\n") + body;
            if (choices.containsKey("Approve for session")) details += "\nSession approval also permits future matching requests.";
            agent.requests.put(rpcId,new Pending(rpcId,kind,payload,"",new JsonObject(),
                object("id",rpcId,"kind","approval","title",title,"details",details,"options",choices.keySet()),choices));
        } else {
            fail(agent,new IOException("Unsupported interaction form: " + kind));
        }
        if (!agent.requests.isEmpty()) agent.status = "waiting";
        childNotice(agent, rpcId, "attention", "Needs user attention. Open this child’s requests; this notice does not grant approval.\n" + agent.requests.values().stream().map(p -> text(p.display,"title")).toList());
        save();
    }
    private void interactionRecord(Agent agent,String id,JsonObject payload,String status,JsonObject answer) {
        JsonObject saved = payload.deepCopy(); saved.remove("availableDecisions");
        JsonElement resolution = JsonNull.INSTANCE;
        if (answer != null && text(payload,"kind").equals("user_question")) {
            resolution = object("kind","user_answer","answers",obj(answer,"answers"));
        } else if (answer != null) {
            resolution = object("decision",text(answer,"decision"));
            if (!text(answer,"decision").equals("deny")) resolution.getAsJsonObject().add("grantedPermissions",answer.has("grantedPermissions") ? answer.get("grantedPermissions").deepCopy() : JsonNull.INSTANCE);
        }
        record(agent,object("type","system/interaction/lifecycle","interaction",object("id",id,"status",status,"statusReason",null,
            "origin",object("kind","provider","providerId",agent.providerId,"providerRequestId",id),"payload",saved,"resolution",resolution)));
    }
    private void closeRequests(Agent agent,String status) {
        Set<String> closed = new HashSet<>();
        for (Pending pending : agent.requests.values()) if (closed.add(pending.rpcId)) interactionRecord(agent,pending.rpcId,pending.params,status,null);
        agent.requests.clear(); agent.responsesInFlight.clear();
    }

    private static JsonObject questionReply(String message) {
        if (!message.startsWith(QUESTION_REPLY)) return null;
        try {
            JsonObject reply = JsonParser.parseString(message.substring(QUESTION_REPLY.length())).getAsJsonObject();
            return text(reply, "questionId").isBlank() ? null : reply;
        } catch (RuntimeException invalid) { return null; }
    }

    /** Record harness acceptance before saving, so a disk failure cannot offer the same reply again. */
    private void resolveQuestionReply(Agent agent, String message) {
        JsonObject reply = questionReply(message);
        if (reply == null) return;
        String id = text(reply, "questionId");
        agent.answeredQuestions.add(id);
        JsonObject question = agent.asyncQuestions.remove(id);
        if (question != null) {
            JsonObject answers = new JsonObject();
            String rpcId = text(question,"rpcId");
            String nativeQuestion = id.startsWith(rpcId + ":") ? id.substring(rpcId.length()+1) : id;
            answers.add(nativeQuestion,object("selected",List.of(),"freeText",text(reply,"answer")));
            interactionRecord(agent,rpcId,obj(question,"payload"),"resolved",object("kind","user_question","answers",answers));
        }
    }

    private synchronized void fail(Agent agent, Throwable failure) {
        if (!active(agent)) return;
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof AgentHarness.RequestFailure request) agent.recovery = object("kind",request.recovery,"knownRejected",request.rejected,"details",request.details);
        agent.lastError = message(failure);
        agent.errorVersion++;
        agent.status = agent.turnSession == null ? "error" : "running";
        add(agent, "system", message(failure));
        if (!agent.stopping) childNotice(agent, agent.activeTurnId == null ? "failure-" + UUID.randomUUID() : agent.activeTurnId, "failed",message(failure));
    }

    private void add(Agent agent, String role, String text) {
        if (text == null || text.isBlank()) return;
        record(agent, object("type", "system/error", "code", "notice", "message", text));
    }
    private CompletableFuture<Void> record(Agent agent, JsonObject event) {
        event.addProperty("threadId", agent.id);
        event.addProperty("providerId", agent.providerId);
        if (text(event,"providerThreadId").isBlank() && agent.providerSessionId != null) event.addProperty("providerThreadId",agent.providerSessionId);
        if (!event.has("scope")) event.add("scope", object("kind", "thread"));
        SharedModel.validateEvent(event);
        if (!agent.historyError.isBlank()) return CompletableFuture.failedFuture(new IOException(agent.historyError));
        return agent.history.append(event).whenComplete((ignored,error) -> {
            if (error == null) return;
            synchronized(this) {
                if (!agent.historyError.isBlank()) return;
                agent.historyError = "Conversation history could not be saved: " + message(error);
                agent.lastError = agent.historyError;
                agent.errorVersion++;
                agent.stopping = true;
                closeTools(agent,"history_unavailable");
                if (agent.turnSession != null) game.requestActionStop(agent.body,agent.turnSession);
                if (agent.session != null && agent.activeTurn) connection(agent).interrupt(agent.session);
                agent.status = "error";
            }
        });
    }
    @Override public CompletableFuture<JsonObject> transcript(String id, JsonObject query) {
        var copy=query.deepCopy();
        return ready.thenCompose(unused -> CompletableFuture.supplyAsync(() -> {
            ConversationHistory history; long replyVersion;
            synchronized(this) { var agent=agents.get(id); if(agent==null) throw new IllegalArgumentException("Unknown conversation"); history=agent.history; replyVersion=agent.replyVersion; }
            var result=history.transcript(copy);result.addProperty("replyVersion",replyVersion);return result;
        }));
    }

    private String requested(Agent agent, String message, String kind, JsonObject origin, List<String> images) {
        if (agent.taskTitle.isBlank() && (origin == null || text(origin,"kind").equals("task"))) {
            String title = conciseTitle(origin == null ? message : text(origin,"text"));
            if (!title.isBlank()) {
                agent.taskTitle = title;
                agent.taskTitleSource = "request";
            }
        }
        String alphabet = "23456789abcdefghijkmnpqrstuvwxyz";
        StringBuilder generated = new StringBuilder("creq_");
        for (int i=0;i<10;i++) generated.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        String id = generated.toString();
        record(agent, object("type", "client/turn/requested", "direction", "outbound", "requestId", id, "source", "tell", "initiator", origin == null ? "user" : text(origin,"kind").equals("message") || text(origin,"kind").equals("task") ? "agent" : "system", "senderThreadId", origin == null ? null : text(origin,"senderId"),
            "input", AgentHarness.Input.withImages(message, images).content(),
            "target", object("kind", kind, "expectedTurnId", agent.activeTurnId), "request", object("method", "turn/start", "params", new JsonObject()),
            "execution", object("model", agent.model, "serviceTier", agent.serviceTier.equals("priority") ? "fast" : "default", "reasoningLevel", agent.effort,
                "permissionMode", connection(agent).permissionMode(options(agent)), "source", "client/turn/requested", "providerOptions", options(agent).permissions())));
        return id;
    }

    /** Provider titles win; a clean request preview keeps untitled conversations identifiable. */
    private void restoreTaskTitle(Agent agent) {
        String nativeTitle = conciseTitle(text(obj(agent.history.stateSnapshot(),"state"),"name"));
        if (!nativeTitle.isBlank() && !agent.taskTitleSource.equals("set")) {
            agent.taskTitle = nativeTitle;
            agent.taskTitleSource = "provider";
        }
        if (!agent.taskTitle.isBlank()) return;
        for (var entry : array(agent.history.snapshot(),"rows")) {
            JsonObject row = entry.getAsJsonObject(), request = obj(row,"request");
            if (!text(row,"kind").equals("userMessage") || !text(request,"initiator").equals("user")) continue;
            String title = conciseTitle(text(row,"text"));
            if (title.isBlank()) continue;
            agent.taskTitle = title;
            agent.taskTitleSource = "request";
            return;
        }
    }

    private static String conciseTitle(String value) {
        if (value == null || value.isBlank()) return "";
        String title = value.strip();
        if (title.startsWith(QUESTION_REPLY.strip()) || title.contains("Minecraft context supplied")
                || title.startsWith("[Agent coordination:") || title.startsWith("Too Many Agents initialized")) return "";
        title = title.replaceAll("\\s+", " ").replaceFirst("^#{1,6}\\s+", "");
        if (title.codePointCount(0,title.length()) <= 80) return title;
        int end = title.offsetByCodePoints(0,77), space = title.lastIndexOf(' ',end);
        if (space >= 48) end = space;
        return title.substring(0,end).stripTrailing() + "…";
    }

    private static boolean active(Agent agent) { return agent.lifecycle.equals("active") && !agent.conversationArchived; }

    private synchronized Agent require(String id) {
        if (closed || shuttingDown) throw new IllegalStateException("Agent service is closing.");
        Agent agent = agents.get(id);
        if (agent == null) throw new IllegalArgumentException("Unknown agent: " + id);
        if (!active(agent)) throw new IllegalStateException("agent_not_active: " + agent.lifecycle);
        return agent;
    }

    private void load() {
        if (!Files.exists(storage)) return;
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(storage)).getAsJsonObject();
            synchronized (this) {
                if (saved.has("defaultPermissionMode")) defaultPermissionMode = text(saved,"defaultPermissionMode");
                if(saved.has("profiles")) for (var entry : saved.getAsJsonObject("profiles").entrySet()) profiles.put(entry.getKey(),entry.getValue().getAsJsonObject().deepCopy());
                var pendingRows = new ArrayList<JsonObject>();
                for (var value : array(saved,"agents")) pendingRows.add(value.getAsJsonObject());
                var orderedRows = new ArrayList<JsonObject>();
                while (!pendingRows.isEmpty()) {
                    var pendingIds = pendingRows.stream().map(row -> text(row,"id")).collect(java.util.stream.Collectors.toSet());
                    var next = pendingRows.stream().filter(row -> !pendingIds.contains(text(row,"parentId"))).findFirst()
                        .orElseThrow(() -> new IOException("Saved agent parent links contain a cycle; file preserved."));
                    orderedRows.add(next); pendingRows.remove(next);
                }
                for (JsonElement value : orderedRows) {
                    JsonObject row = value.getAsJsonObject();
                    Agent agent = new Agent();
                    agent.strategy = row.has("strategy") ? row.get("strategy").deepCopy() : JsonNull.INSTANCE;
                    // Saves name only the package id; older pins are rewritten to the installed package.
                    try { agent.strategy = MinecraftStrategy.resolve(agent.strategy).reference(); } catch (IllegalStateException ignored) {}
                    agent.id = text(row, "id"); agent.name = text(row, "name");
                    agent.taskTitle = conciseTitle(text(row,"taskTitle"));
                    agent.taskTitleSource = agent.taskTitle.isBlank() ? "" : text(row,"taskTitleSource");
                    agent.projectId = text(row,"projectId");
                    agent.checkoutId = text(row,"checkoutId");
                    projects.checkout(agent.projectId,agent.checkoutId);
                    agent.providerId = text(row,"providerId");
                    agent.parentId = text(row,"parentId");
                    agent.nativeSubagentsEnabled = row.has("nativeSubagentsEnabled") && row.get("nativeSubagentsEnabled").getAsBoolean();
                    agent.holdMessages = row.has("holdMessages") && row.get("holdMessages").getAsBoolean() || row.has("turnInFlight") && row.get("turnInFlight").getAsBoolean();
                    for (var valueMail : array(row,"mailbox")) {
                        var savedMail = valueMail.getAsJsonObject().deepCopy();
                        if (text(savedMail,"state").equals("attempting")) { savedMail.addProperty("state","uncertain"); agent.holdMessages = true; }
                        agent.mailbox.add(savedMail);
                    }
                    for (var suppressed : array(row,"suppressedNotices")) agent.suppressedNotices.add(suppressed.getAsString());
                    agent.resumeRequired = !row.has("resumeRequired") || row.get("resumeRequired").getAsBoolean();
                    agent.providerSessionId = text(row, "providerSessionId"); agent.model = text(row, "model"); agent.effort = text(row, "effort");
                    agent.serviceTier = tier(text(row, "serviceTier"));
                    agent.replyVersion = row.has("replyVersion") ? Math.max(0, row.get("replyVersion").getAsLong()) : 0;
                    agent.readVersion = row.has("readVersion") ? Math.clamp(row.get("readVersion").getAsLong(), 0, agent.replyVersion) : 0;
                    for (JsonElement answered : array(row, "answeredQuestions")) agent.answeredQuestions.add(answered.getAsString());
                    agent.body = JSON.fromJson(row.get("body"), GameAccess.Body.class);
                    if (row.has("settings")) agent.settings = row.getAsJsonObject("settings").deepCopy();
                    agent.permissions = connection(agent).normalizePermissions(row.getAsJsonObject("permissions"));
                    if (row.has("lifecycle")) agent.lifecycle = text(row, "lifecycle");
                    agent.bodyLost = row.has("bodyLost") && row.get("bodyLost").getAsBoolean();
                    agent.bodyRemoved = row.has("bodyRemoved") && row.get("bodyRemoved").getAsBoolean();
                    agent.archiveRequested = row.has("archiveRequested") && row.get("archiveRequested").getAsBoolean();
                    agent.threadArchived = row.has("threadArchived") && row.get("threadArchived").getAsBoolean();
                    agent.conversationArchived = row.has("conversationArchived") && row.get("conversationArchived").getAsBoolean();
                    agent.removalError = text(row, "removalError");
                    if (agent.lifecycle.equals("removing") || agent.lifecycle.equals("archiving")) {
                        agent.lifecycle = agent.bodyRemoved && agent.archiveRequested ? "archive_failed" : "removal_failed";
                        agent.removalError = "The previous removal was interrupted. Retry the same operation; a missing or unloaded body requires checking its original world first.";
                    }
                    if (!active(agent)) { agent.status = agent.lifecycle; agent.stopping = true; }
                    if (agent.id.isBlank() || agent.providerSessionId.isBlank() || agent.body == null) throw new IOException("Incomplete saved agent association.");
                    initializeModel(agent);
                    try { agent.history.load().join(); restoreTaskTitle(agent); }
                    catch (CompletionException failure) { agent.historyError = message(failure); agent.status = "error"; }
                    agents.put(agent.id, agent);
                }
                recoverChildNotices();
            }
        } catch (Exception e) { throw new CompletionException(new IOException("Could not read saved agents; the file was preserved: " + storage, e)); }
    }

    private void recoverChildNotices() throws IOException {
        for (Agent child : agents.values()) {
            // Archived chats move to the archive folder and owe their parent nothing.
            if (child.parentId.isBlank() || !child.historyError.isBlank() || child.conversationArchived) continue;
            Path log = storage.getParent().resolve("conversations").resolve(child.id + ".events.jsonl");
            var roots = new HashSet<String>();
            try (var lines = Files.lines(log)) {
                for (var iterator = lines.iterator(); iterator.hasNext();) {
                    var envelope = JsonParser.parseString(iterator.next()).getAsJsonObject();
                    var data = obj(envelope,"data");
                    String type = text(envelope,"type"), turn = text(obj(envelope,"scope"),"turnId");
                    if (type.equals("turn/started") && !data.has("parentToolCallId")) roots.add(turn);
                    if (type.equals("turn/completed") && roots.remove(turn) && Set.of("completed","failed").contains(text(data,"status")))
                        childNotice(child,turn,text(data,"status"),resultText(child,turn));
                }
            }
        }
    }

    private synchronized CompletableFuture<Void> save() {
        JsonArray rows = new JsonArray();
        for (Agent agent : agents.values()) if (agent.body != null) rows.add(object("id", agent.id, "name", agent.name, "projectId",agent.projectId,"checkoutId",agent.checkoutId,
                "strategy",agent.strategy,"body", agent.body, "providerId", agent.providerId, "providerSessionId", agent.providerSessionId, "resumeRequired", agent.resumeRequired, "model", agent.model, "effort", agent.effort,"settings",agent.settings,"permissions",agent.permissions,
                "serviceTier",agent.serviceTier,"replyVersion",agent.replyVersion,"readVersion",agent.readVersion,
                "taskTitle",agent.taskTitle,"taskTitleSource",agent.taskTitleSource,
                "answeredQuestions",agent.answeredQuestions,
                "parentId",agent.parentId,"nativeSubagentsEnabled",agent.nativeSubagentsEnabled,"holdMessages",agent.holdMessages,"turnInFlight",agent.turnSession != null,
                "mailbox",agent.mailbox,"suppressedNotices",agent.suppressedNotices,
                "lifecycle",agent.lifecycle,"conversationArchived",agent.conversationArchived,"bodyRemoved",agent.bodyRemoved,"bodyLost",agent.bodyLost,"archiveRequested",agent.archiveRequested,"threadArchived",agent.threadArchived,"removalError",agent.removalError));
        String json = JSON.toJson(object("defaultPermissionMode",defaultPermissionMode,"agents", rows,"profiles",profiles));
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(storage.getParent());
                Path temporary = Files.createTempFile(storage.getParent(), "agents-", ".tmp");
                try {
                    try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                        var bytes = java.nio.ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                        while (bytes.hasRemaining()) channel.write(bytes);
                        channel.force(true);
                    }
                    Files.move(temporary, storage, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(temporary); }
            } catch (IOException e) { throw new CompletionException(new IOException("Could not save agent associations: " + e.getMessage(), e)); }
        }, disk).whenComplete((unused, failure) -> { synchronized (this) { storageError = failure == null ? "" : message(failure); } });
    }

    private static boolean minecraftAccess(Agent agent) {
        return !agent.strategy.isJsonObject() || !text(agent.strategy.getAsJsonObject(), "id").equals("work");
    }

    private String instructions(Agent agent) {
        if (!minecraftAccess(agent)) return MinecraftStrategy.resolve(agent.strategy).instructions(agent.providerId);
        return "You are " + agent.name + ", a coding agent embodied as an NPC in the user's singleplayer Minecraft world. "
            + "Your working directory is " + directory(agent) + ". Follow its AGENTS.md and use ordinary coding tools for project work. "
            + "Initial model: " + agent.model + ", reasoning effort: " + agent.effort + ". "
            + MinecraftStrategy.resolve(agent.strategy).instructions(agent.providerId);
    }

    private static JsonObject obj(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static String tier(String value) { return value == null || value.isBlank() ? "default" : value; }
    private static String text(JsonObject object, String key) { JsonElement value = object.get(key); return value != null && value.isJsonPrimitive() ? value.getAsString() : ""; }
    private static JsonObject object(Object... values) { JsonObject object = new JsonObject(); for (int i = 0; i < values.length; i += 2) object.add((String) values[i], JSON.toJsonTree(values[i + 1])); return object; }
    private static <T> CompletableFuture<T> failed(String message) { return CompletableFuture.failedFuture(new IllegalStateException(message)); }
    private static String message(Throwable error) { while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause(); return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    /** Called by the JVM shutdown hook, never by a game tick. */
    @Override public void close() {
        List<Agent> savedAgents;
        List<Connection> owned;
        synchronized (this) {
            if (closed || shuttingDown) return;
            shuttingDown = true;
            coordination.shutdownNow();
            savedAgents = List.copyOf(agents.values());
            owned = List.copyOf(connections.values());
            for (Agent agent : savedAgents) {
                agent.stopping = true;
                closeTools(agent, "service_closed");
                clearQueue(agent, "service closed");
            }
        }
        List<CompletableFuture<Void>> settlements = new ArrayList<>();
        for (Agent agent : savedAgents) {
            AgentHarness.Session session = agent.session;
            if (session == null) continue;
            try {
                AgentHarness connector = connection(agent);
                settlements.add(connector.interrupt(session).thenCompose(done -> connector.release(session)));
            } catch (RuntimeException error) { LOG.warn("Session shutdown: {}", message(error)); }
        }
        try { CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new)).get(18, TimeUnit.SECONDS); }
        catch (Exception error) { LOG.warn("Session shutdown incomplete: {}", message(error)); }
        synchronized (this) {
            for (Agent agent : savedAgents) closeRequests(agent,"interrupted");
            closed = true;
        }
        owned.forEach(connection -> connection.harness().close());
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (Agent agent : savedAgents) if (agent.history != null) writes.add(agent.history.closeAsync());
        try { CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS); }
        catch (Exception error) { LOG.warn("History shutdown incomplete: {}", message(error)); }
        synchronized (this) { connections.clear(); }
        projectJobs.shutdown();
        disk.shutdown();
        try { if (!disk.awaitTermination(5,TimeUnit.SECONDS)) LOG.warn("Association writes did not finish before shutdown."); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
