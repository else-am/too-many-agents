package toomanyagents;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import toomanyagents.ui.AgentUiAccess;
import static toomanyagents.JsonState.*;
import toomanyagents.screens.ScreenTools;

/** Minecraft owns agents and bodies; BB owns conversations, providers, projects and queues. */
final class AgentService implements AgentUiAccess, AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private final GameAccess game;
    private final Supplier<String> worldSession;
    private final BbClient bb;
    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("minecraft-bb").factory());
    private final ExecutorService disk = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("minecraft-bodies").factory());
    private final Map<String,Agent> agents = new LinkedHashMap<>();
    // Physical tool calls in flight, by the plugin's request ID.
    private final Map<String,GameAccess.ToolScope> scopes = new HashMap<>();
    private final Map<String,String> scopeAgents = new HashMap<>();
    private final Map<String,String> scopeScripts = new HashMap<>();
    // A cancel can arrive before the call it cancels.
    private final Set<String> cancelled = new HashSet<>();
    /** Requests that are screen calls, whose cancellation must not stop the body's physical work. */
    private final Set<String> screenScopes = new HashSet<>();

    private JsonArray projectRows = new JsonArray();
    private Path records;
    private String loadedSession, loadedWorldId, callbackUrl, callbackToken, connectedInstance = "", error = "";
    private boolean connected, closed;
    private String connectionId = UUID.randomUUID().toString();

    private static final class Agent {
        String id, name, bbInstanceId="", projectId="", threadId="", parentThreadId="", suspendedBody="", startNonce="";
        GameAccess.Body body;
        JsonObject settings=new JsonObject();
        // Opaque plugin-owned draft, retained with an unstarted body.
        JsonObject spawn=new JsonObject();
        // The plugin's latest UI view; Java does not derive conversation state.
        JsonObject remote=new JsonObject();
        boolean removed, lost, archived, deleted, suspended;
        Boolean localArchive;
    }

    AgentService(GameAccess game, Supplier<String> worldSession) {
        this.game=game; this.worldSession=worldSession; bb=new BbClient();
        BbSetup.get().setAgents(this);
        // BB pushes changes; this slower sync re-attaches after a plugin restart and catches anything missed.
        polling.scheduleWithFixedDelay(this::sync,0,5,TimeUnit.SECONDS);
    }
    synchronized void bridgeConnection(String url,String token) { callbackUrl=url+"/v1/bb"; callbackToken=token; }

    private void sync() {
        if(closed || callbackUrl==null) return;
        String session=worldSession.get();
        if(session==null) return;
        var world=game.worldInfo();
        String worldId=text(world,"id");
        if(worldId.isBlank() || flag(world,"needsDecision") || !worldId.equals(game.currentWorldId())) return;
        String instance = "", attachment;
        try {
            synchronized(this) {
                if(!session.equals(worldSession.get())) return;
                if(!session.equals(loadedSession)) loadWorld(world,session);
                attachment=connectionId;
            }
            instance=BbSetup.get().connection().instanceId();
            synchronized(this) {
                if(!instance.equals(connectedInstance)) { closeScopes(null,"bb_changed"); projectRows(new JsonArray()); connected=false; connectedInstance=instance; }
            }
            bb.call(object("op","session.attach","connectionId",attachment,"bbInstanceId",instance,"protocol",3,"worldId",worldId,"worldSessionId",session,"callbackUrl",callbackUrl,"callbackToken",callbackToken)).join();
            synchronized(this) { if(!currentSession(session) || !attachment.equals(connectionId)) return; }
            var state=rpc("world.sync",object("bbInstanceId",instance)).join().getAsJsonObject();
            synchronized(this) {
                if(!currentSession(session) || !attachment.equals(connectionId) || !BbSetup.get().isCurrent(instance)) return;
                projectRows(array(state,"projects")); connected=true; error="";
                game.projectCatalog(instance,projectRows,session);
                // Old drafts have no conversation metadata. Their existing project can
                // identify the BB that owns their saved execution choices.
                var projects=new HashSet<String>();
                for(var row:projectRows) projects.add(text(row.getAsJsonObject(),"id"));
                boolean adopted=false;
                for(var agent:agents.values()) if(agent.bbInstanceId.isBlank() && agent.threadId.isBlank() && agent.startNonce.isBlank()
                    && !agent.deleted && projects.contains(agent.projectId)) { agent.bbInstanceId=instance; adopted=true; }
                if(adopted) save();
            }

        } catch(Exception failure) {
            synchronized(this) { if(currentSession(session)) { connected=false; error=message(failure); } }
        }
    }

    /** Apply the physical state requested by the plugin, including after reconnect. */
    private CompletableFuture<Void> applyBodyState(Agent agent,String state,boolean running,String session) {
        synchronized(this) {
            requireCurrent(agent,session);
            if(state.equals("deleted")) {
                closeScopes(agent.id,"conversation_deleted");
                var removal = agent.removed ? CompletableFuture.<Void>completedFuture(null)
                    : !agent.suspendedBody.isBlank() ? CompletableFuture.<Void>completedFuture(null)
                    : game.removeAgent(agent.body,session,agent.id,true);
                return removal.thenCompose(done -> game.releaseStation(agent.id,session)).thenCompose(done -> {
                    synchronized(this) {
                        requireCurrent(agent,session);
                        agent.deleted=true; agent.removed=true; agent.suspendedBody="";
                    }
                    return save();
                });
            }
            if(state.equals("disconnected")) {
                closeScopes(agent.id,"conversation_unavailable");
                if(text(agent.remote,"error").isBlank()) agent.remote.addProperty("error","This conversation is unavailable in BB.");
                return CompletableFuture.completedFuture(null);
            }
            if(state.isBlank()) return CompletableFuture.completedFuture(null);
            if(!Set.of("present","suspended").contains(state)) return failed("unknown_body_state");
            if(!running) closeScopes(agent.id,"thread_not_active");
            boolean archived=agent.localArchive!=null ? agent.localArchive : state.equals("suspended");
            agent.archived=archived;
            if(agent.removed) return CompletableFuture.completedFuture(null);
            if(archived) {
                if(agent.suspended) return CompletableFuture.completedFuture(null);
                var captured=agent.suspendedBody.isBlank()?game.saveBody(agent.body,session):CompletableFuture.completedFuture(agent.suspendedBody);
                return game.releaseStation(agent.id,session).thenCompose(released -> captured).thenCompose(saved -> {
                    synchronized(this) { requireCurrent(agent,session); agent.suspendedBody=saved; }
                    // Inventory has been dropped; persist the empty body before despawning it.
                    return save();
                }).thenCompose(done -> game.suspendBody(agent.body,agent.suspendedBody,session))
                    .thenAccept(done -> { synchronized(this) { requireCurrent(agent,session); agent.suspended=true; } });
            }
            if(!agent.suspendedBody.isBlank()) {
                return game.restoreSavedBody(agent.body,agent.suspendedBody,session).thenCompose(done -> {
                    synchronized(this) { requireCurrent(agent,session); agent.suspendedBody=""; agent.suspended=false; agent.lost=false; }
                    return save();
                });
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private void loadWorld(JsonObject world,String session) throws Exception {
        closeScopes(null,"world_session_changed");
        agents.clear(); cancelled.clear(); connected=false; connectedInstance=""; projectRows(new JsonArray());
        String worldId=text(world,"id");
        records=Path.of(text(world,"directory")).resolve("too-many-agents/bb-bodies.json");
        if(Files.exists(records)) {
            var saved=JsonParser.parseString(Files.readString(records)).getAsJsonObject();
            if(!text(saved,"worldId").equals(worldId)) throw new IllegalStateException("Saved bodies belong to a different world identity.");
            for(var value:array(saved,"agents")) loadAgent(value.getAsJsonObject(),worldId);
        }
        loadedSession=session; loadedWorldId=worldId;
        for(var agent:agents.values()) {
            settleKind(agent,agent.settings);
            if(agent.archived || agent.removed || agent.deleted) game.releaseStation(agent.id,session);
        }
        save();
    }
    private void loadAgent(JsonObject row,String worldId) {
        var agent=new Agent(); agent.id=text(row,"id"); agent.name=text(row,"name");
        UUID.fromString(agent.id);
        agent.body=JSON.fromJson(row.get("body"),GameAccess.Body.class);
        if(agent.body!=null) agent.body=new GameAccess.Body(agent.body.entityUuid(),worldId,agent.body.dimension());
        agent.settings=BodySettings.copy(obj(row,"settings"));
        if(agent.settings.has("behaviors")) try { validateBehaviors(agent.settings.get("behaviors")); }
            catch(IllegalArgumentException obsolete) { agent.settings.remove("behaviors"); }
        if(agent.settings.has("behavior")) try { BodySettings.behavior(text(agent.settings,"behavior")); }
            catch(IllegalArgumentException obsolete) { agent.settings.remove("behavior"); }
        agent.projectId=text(row,"projectId"); agent.threadId=text(row,"threadId"); agent.suspendedBody=text(row,"suspendedBody"); agent.deleted=flag(row,"deleted");
        agent.bbInstanceId=text(row,"bbInstanceId");
        agent.localArchive=row.has("localArchive") && !row.get("localArchive").isJsonNull() ? row.get("localArchive").getAsBoolean() : null;
        agent.startNonce=text(row,"startNonce");
        agent.spawn=obj(row,"spawn").deepCopy(); agent.archived=flag(row,"archived");
        agent.removed=flag(row,"bodyRemoved"); agent.lost=flag(row,"bodyLost");
        agents.put(agent.id,agent);
    }
    private synchronized CompletableFuture<Void> save() {
        if(records==null) return CompletableFuture.completedFuture(null);
        var rows=new JsonArray();
        for(var agent:agents.values()) rows.add(object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,
            "projectId",agent.projectId,"threadId",agent.threadId,"suspendedBody",agent.suspendedBody,"deleted",agent.deleted,
            "bbInstanceId",agent.bbInstanceId,"localArchive",agent.localArchive,
            "startNonce",agent.startNonce,"spawn",agent.spawn,"archived",agent.archived,"bodyRemoved",agent.removed,"bodyLost",agent.lost));
        var data=object("worldId",loadedWorldId,"agents",rows); var file=records;
        return CompletableFuture.runAsync(() -> { try { JsonState.write(file,data); } catch(Exception failure) { throw new CompletionException(failure); } },disk);
    }
    private synchronized CompletableFuture<JsonElement> rpc(String op,JsonObject arguments) {
        var request=arguments.deepCopy(); request.addProperty("op",op);
        request.addProperty("worldId",loadedWorldId); request.addProperty("worldSessionId",loadedSession);
        String attachment=connectionId;
        request.addProperty("connectionId",attachment);
        return bb.call(request).thenApply(reply -> {
            synchronized(this) { if(!attachment.equals(connectionId)) throw new CompletionException(new IllegalStateException("BB reconnected while the request was running. Its outcome is unknown; inspect it before retrying.")); }
            return reply;
        });
    }
    private synchronized CompletableFuture<JsonElement> agentRpc(String op,String id,JsonObject arguments) {
        return guarded(() -> {
            var agent=require(id);
            if(!ownsConnection(agent)) return failed("This agent is disconnected. Select its BB to reconnect.");
            var request=arguments.deepCopy(); request.addProperty("agentId",id); request.addProperty("bbInstanceId",agent.bbInstanceId);
            return rpc(op,request);
        });
    }
    private void projectRows(JsonArray rows) { projectRows=rows; game.stationProjects(rows); }
    /** Whether a project is this world's own project in the selected BB. */
    private boolean worldProject(String projectId) {
        return !projectId.isBlank() && projectId.equals(text(worldView(),"worldProjectId"));
    }
    /** This world's project in the agent's own BB, or "" until that BB has mapped one. */
    private String ownWorldProject(Agent agent) {
        return agent.bbInstanceId.isBlank() ? "" : text(obj(game.worldInfo(),"bbProjects"),agent.bbInstanceId);
    }
    /** World agents, in their world's own project, have Minecraft access; project agents never do. */
    private boolean minecraftAccess(Agent agent) {
        String own=ownWorldProject(agent);
        return !own.isBlank() && own.equals(agent.projectId);
    }
    /** Drops the other kind's settings once the agent's kind is known. An unmapped world or copy keeps both. */
    private void settleKind(Agent agent,JsonObject settings) {
        if(!ownWorldProject(agent).isBlank() && !agent.projectId.equals("minecraft")) BodySettings.forProject(settings,minecraftAccess(agent));
    }
    /** The station this agent occupies, from the index's published snapshot, or "". */
    private String stationId(Agent agent) {
        for(var row:array(game.worldInfo(),"stations")) if(text(row.getAsJsonObject(),"agentId").equals(agent.id)) return text(row.getAsJsonObject(),"id");
        return "";
    }
    private boolean ownsConnection(Agent agent) { return !agent.bbInstanceId.isBlank() && BbSetup.get().isCurrent(agent.bbInstanceId); }
    private JsonObject worldView() { return worldView(game.worldInfo(),BbSetup.get().selectedInstance()); }
    private static JsonObject worldView(JsonObject world,String instance) {
        world.addProperty("legacyWorldProjectId",text(world,"worldProjectId"));
        world.addProperty("worldProjectId",text(obj(world,"bbProjects"),instance));
        return world;
    }
    private synchronized Agent require(String id) {
        if(!currentSession(loadedSession)) throw new IllegalStateException("world_session_changed");
        var agent=agents.get(id); if(agent==null) throw new IllegalArgumentException("Unknown agent: "+id); return agent;
    }
    private boolean currentSession(String session) { return session!=null && session.equals(loadedSession) && session.equals(worldSession.get()); }
    private boolean currentAgent(Agent agent,String session) { return currentSession(session) && agents.get(agent.id)==agent; }
    private void requireCurrent(Agent agent,String session) { if(!currentAgent(agent,session)) throw new IllegalStateException("world_session_changed"); }
    synchronized String findByBody(UUID body) { return currentSession(loadedSession) ? agents.values().stream().filter(a -> a.body!=null && a.body.entityUuid().equals(body.toString()) && game.belongsToCurrentWorld(a.body)).map(a -> a.id).findFirst().orElse(null) : null; }
    /** Null until this world's records load; an empty registry then really means no bodies belong here. */
    synchronized Set<String> retainedBodies() {
        if(!currentSession(loadedSession)) return null;
        // Connection failures never remove bodies or their saved bindings.
        return agents.values().stream().filter(a -> !a.removed && !a.deleted).map(a -> a.id).collect(Collectors.toSet());
    }

    /** A loaded, tagged body observed by the owning server thread; never a replacement or recovery. */
    void bodyObserved(String id, GameAccess.Body observed, String session) {
        GameAccess.Body previous;
        synchronized(this) {
            if (!currentSession(session)) return;
            var agent = agents.get(id);
            if (agent == null || agent.removed || agent.archived || agent.body == null
                || !observed.world().equals(loadedWorldId)
                || !observed.world().equals(agent.body.world())
                || !observed.entityUuid().equals(agent.body.entityUuid())
                || observed.equals(agent.body)) return;
            previous = agent.body;
            agent.body = observed;
            save();
        }
        // Revoking queued work can complete callbacks into AgentService.
        game.forgetDimension(previous);
    }
    synchronized Map<String,GameAccess.AgentState> agentStates() {
        if(!connectedInstance.isBlank() && !BbSetup.get().isCurrent(connectedInstance)) {
            closeScopes(null,"bb_disconnected"); connected=false; connectedInstance=""; projectRows(new JsonArray());
        }
        var result=new HashMap<String,GameAccess.AgentState>();
        if(!currentSession(loadedSession)) return result;
        for(var agent:agents.values()) if(!agent.removed && !agent.archived) {
            String activity=text(snapshot(agent.id),"activity");
            result.put(agent.id,new GameAccess.AgentState(agent.projectId,activity,minecraftAccess(agent)));
        }
        return result;
    }
    @Override public synchronized JsonArray list() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) if(!agent.deleted) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonArray worldAgents() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) if(!agent.removed && !agent.archived && game.belongsToCurrentWorld(agent.body)) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonObject snapshot(String id) {
        var agent=agents.get(id); if(agent==null || !currentSession(loadedSession)) return object("id",id,"status","unavailable");
        String agentError=text(agent.remote,"error");
        var state=agent.remote.deepCopy();
        for(var row:ownsConnection(agent)?projectRows:new JsonArray()) {
            var project=row.getAsJsonObject();
            if(agent.projectId.equals(text(project,"id"))) {
                state.addProperty("projectColor",ProjectColor.of(project));
                state.addProperty("projectName",text(project,"name"));
                break;
            }
        }
        var physical=object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,"projectId",agent.projectId,
            "threadId",agent.threadId,"bbInstanceId",agent.bbInstanceId,"connectionId",connectionId,"lifecycle",agent.removed?"removed":"active","bodyRemoved",agent.removed,"bodyLost",agent.lost,
            "minecraftAccess",minecraftAccess(agent),"stationId",stationId(agent),"currentWorld",game.belongsToCurrentWorld(agent.body),
            "bodyType",text(agent.settings,"body"),"gamePaused",game.isPaused(),"conversationArchived",agent.archived);
        for(var entry:physical.entrySet()) state.add(entry.getKey(),entry.getValue());
        if(agent.threadId.isBlank()) { state.add("executionOptions",agent.spawn); state.addProperty("providerId",text(agent.spawn,"providerId")); }
        if(!state.has("status")) state.addProperty("status","idle");
        if(!state.has("activity")) state.addProperty("activity","idle");
        if(!connected || !ownsConnection(agent) || !agentError.isBlank()) {
            state.addProperty("status","disconnected"); state.addProperty("activity","idle"); state.addProperty("canSteer",false); state.add("speech",JsonNull.INSTANCE);
        }
        var live=agent.body==null?new JsonObject():game.cached(agent.body);
        state.addProperty("bodyLoaded",!live.isEmpty());
        if(live.has("action")) state.add("action",live.get("action"));
        int pending=scopes.entrySet().stream().filter(e -> agent.id.equals(scopeAgents.get(e.getKey()))).mapToInt(e -> e.getValue().pendingCount()).sum();
        state.addProperty("pendingWorldTools",pending); state.addProperty("waitingForGame",game.isPaused() && pending>0);
        if(!ownsConnection(agent)) state.addProperty("error","Select this agent's BB to reconnect. Its conversation binding is saved.");
        else if(!agentError.isBlank()) state.addProperty("error",agentError);
        else if(!error.isBlank()) state.addProperty("error",error);
        return state;
    }
    @Override public synchronized void markRead(String id,long version) { if(currentSession(loadedSession) && connected && !require(id).threadId.isBlank()) agentRpc("agent.markRead",id,new JsonObject()).exceptionally(f -> null); }
    @Override public CompletableFuture<JsonObject> backendStatus() { return rpc("backend.status",new JsonObject()).thenApply(value -> { var status=value.getAsJsonObject(); status.addProperty("connected",connected); status.addProperty("error",error); return status; }).exceptionally(failure -> object("connected",false,"error",message(failure))); }
    @Override public CompletableFuture<JsonObject> backendConfig() { return rpc("system.config",new JsonObject()).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> setDefaultProvider(String providerId) { return rpc("system.defaultProvider.set",object("providerId",providerId)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> projectExecutionOptions(String projectId) {
        return projectExecutionOptions(projectId,false);
    }
    @Override public CompletableFuture<JsonObject> projectExecutionOptions(String projectId,boolean station) {
        return rpc("project.executionOptions",object("projectId",projectId,"station",station)).thenApply(value -> value.isJsonObject()?value.getAsJsonObject():new JsonObject());
    }
    @Override public CompletableFuture<JsonObject> usage() { return rpc("usage",new JsonObject()).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> catalog() { return catalog(""); }
    @Override public CompletableFuture<JsonObject> catalog(String providerId) { return catalog(providerId,""); }
    @Override public CompletableFuture<JsonObject> catalog(String providerId,String environmentId) {
        var args=new JsonObject(); if(providerId!=null && !providerId.isBlank()) args.addProperty("providerId",providerId);
        if(environmentId!=null && !environmentId.isBlank()) args.addProperty("environmentId",environmentId);
        return rpc("catalog",args).thenCombine(game.bodies(),(value,bodies) -> { var result=value.getAsJsonObject().deepCopy(); var names=new JsonArray(); for(var body:bodies) names.add(text(body.getAsJsonObject(),"id")); result.add("bodies",names); return result; });
    }
    @Override public CompletableFuture<JsonObject> projectCreationOptions(String projectId) {
        return rpc("project.creationOptions",object("projectId",projectId)).thenApply(JsonElement::getAsJsonObject);
    }
    @Override public CompletableFuture<JsonObject> blockAction(JsonObject request) { return game.blockUi(request.deepCopy(),worldSession.get()); }

    @Override public synchronized JsonObject projects() { return object("projects",projectRows,"world",worldView()); }
    @Override public CompletableFuture<JsonObject> projectCommand(JsonObject request) { return guarded(() -> {
        String operation=text(request,"operation");
        if(operation.equals("world-resolve")) return game.worldCommand(request.deepCopy(),worldSession.get());
        CompletableFuture<JsonElement> response;
        if(operation.equals("remove")) {
            JsonObject removal; String session=loadedSession, project=text(request,"projectId");
            synchronized(this) {
                var world=game.worldInfo(); // Serialized snapshot published by the server thread.
                if(!currentSession(loadedSession) || !Objects.equals(loadedWorldId,text(world,"id"))
                    || !Objects.equals(loadedWorldId,game.currentWorldId())) throw new IllegalStateException("world_session_changed");
                if(worldProject(project)) throw new IllegalArgumentException("This world's project has no removable project settings.");
                // Its bodies block removal, archived ones too, since restoring brings them back. Its stations stay, blank.
                var bodies=agents.values().stream().filter(a -> !a.removed && !a.deleted && a.projectId.equals(project)).map(a -> a.name).toList();
                if(!bodies.isEmpty()) throw new IllegalStateException("Cannot remove project. First remove its bodies ("+String.join(", ",bodies)+").");
                removal=request.deepCopy(); removal.addProperty("op","project.remove");
                removal.addProperty("worldId",loadedWorldId); removal.addProperty("worldSessionId",loadedSession);
            }
            // BB owns deletion. Do not hold the body lock while contacting it.
            response=rpc("project.remove",removal).thenCompose(done -> game.projectRemoved(project,session).thenApply(cleared -> done));
        } else response=rpc("project."+operation,request);
        if(operation.equals("create")) return response.thenApplyAsync(done -> {
            // Publish the new project before a block screen tries to bind it. sync reports its own errors;
            // a catalog failure must not turn a completed creation into an invitation to create it again.
            sync();
            return done.getAsJsonObject();
        },polling);
        return response.thenApply(JsonElement::getAsJsonObject).whenComplete((done,failure) -> polling.execute(this::sync));
    }); }

    @Override public CompletableFuture<String> spawn(JsonObject request) {
        return rpc("agent.create",object("request",request)).thenApply(result -> text(result.getAsJsonObject(),"agentId"));
    }

    /**
     * Creates and saves a physical body. BB choices are opaque draft data owned by the plugin. A world agent's own
     * project gives Minecraft access; any other project is a project agent's, which may start at one of its stations.
     */
    private CompletableFuture<Agent> createBody(JsonObject request,Agent caller,String session,GameAccess.ToolScope scope,long expiresAt) {
        String id=UUID.randomUUID().toString(), stationId=text(request,"stationId"), requestedProject=text(request,"projectId");
        // "minecraft" names this world's project only in requests; the plugin normally resolves it first.
        String projectId=requestedProject.equals("minecraft") ? text(worldView(),"worldProjectId") : requestedProject;
        if(projectId.isBlank()) return failed("Choose a project.");
        boolean world=worldProject(projectId);
        synchronized(this) {
            if(!world && projectRows.asList().stream().noneMatch(row -> text(row.getAsJsonObject(),"id").equals(projectId) && !text(row.getAsJsonObject(),"kind").equals("world")))
                return failed("Choose this world's project or one of BB's projects. Another world's project can't be used.");
        }
        if(caller!=null && !minecraftAccess(caller) && !caller.projectId.equals(projectId)) return failed("A project agent can spawn only in its own project.");
        if(world && !stationId.isBlank()) return failed("Stations are for project agents. Spawn a world agent without a station.");
        var requested=BodySettings.copy(obj(request,"settings"));
        // Delegated loose bodies start from their caller's body; a station supplies its own.
        var settings=caller==null || !stationId.isBlank()?requested:BodySettings.copy(caller.settings);
        if(settings!=requested) {
            settings.remove("name");
            for(var entry:requested.entrySet()) settings.add(entry.getKey(),entry.getValue());
        }
        // Only world agents have a game mode; project agents keep their behaviors alone.
        BodySettings.forProject(settings,world);
        if(world && !settings.has("mode")) settings.addProperty("mode","survival");
        if(world && !settings.has("behavior")) settings.addProperty("behavior","stand");
        if(world && caller!=null) {
            var mode=BodySettings.mode(text(settings,"mode"));
            var callerMode=BodySettings.mode(text(caller.settings,"mode"));
            if(mode.commands && !callerMode.commands) return failed("A body cannot gain world commands that its caller lacks.");
            if(mode.creative && !callerMode.creative) return failed("A Survival caller cannot give another body Creative access.");
        }
        synchronized(this) {
            if(!currentSession(session)) return failed("world_session_changed");
            if(text(settings,"name").isBlank()) settings.addProperty("name",StarterAgents.name(agents.values().stream().map(a -> a.name).collect(Collectors.toSet())));
        }
        validateBody(settings);
        String name=text(settings,"name"), body=text(settings,"body");
        var agent=new Agent(); agent.id=id; agent.name=name; agent.projectId=projectId; agent.bbInstanceId=text(request,"bbInstanceId");
        agent.settings=settings; agent.spawn=obj(request,"draft").deepCopy();
        var spawned=!stationId.isBlank()
            ? game.spawnAtStation(name,body,StarterAgents.body(),id,projectId,stationId,session,scope,expiresAt)
            : caller==null ? game.spawn(name,body.isBlank()?StarterAgents.body():body,id,session,expiresAt)
            : game.spawnNear(name,body.isBlank()?StarterAgents.body():body,id,caller.body,session,scope,expiresAt);
        return spawned.thenCompose(created -> {
            agent.body=created;
            synchronized(this) {
                if(!Objects.equals(session,loadedSession) || !Objects.equals(session,worldSession.get())) return failed("world_session_changed");
                agents.put(id,agent);
            }
            // Save at once: a later failure must never leave a created body anonymous.
            return save().thenCompose(saved -> game.updateSettings(created,session,settings));
        }).thenCompose(updated -> {
            synchronized(this) { requireCurrent(agent,session); agent.body=updated; adopt(agent,game.settings(updated)); }
            return save().thenApply(done -> agent);
        });
    }
    private CompletableFuture<Void> bind(String id,String threadId,String nonce,String session,String instance) {
        var agent=agents.get(id);
        if(!currentSession(session) || agent==null || !agent.bbInstanceId.isBlank() && !agent.bbInstanceId.equals(instance)) return CompletableFuture.completedFuture(null);
        // Reconnect has checked this thread's BB metadata against the world and agent.
        // This safely identifies records written before BB ownership was recorded.
        if(agent.bbInstanceId.isBlank() && !threadId.isBlank() && agent.threadId.equals(threadId)) {
            agent.bbInstanceId=instance; return save();
        }
        if(!currentSession(session) || agent==null || !agent.threadId.isBlank() || nonce.isBlank() || !agent.startNonce.equals(nonce)) return CompletableFuture.completedFuture(null);
        agent.bbInstanceId=instance; agent.threadId=threadId; agent.startNonce=""; agent.spawn=new JsonObject();
        return save();
    }
    private JsonObject bodyRecord(Agent agent) {
        return object("agentId",agent.id,"name",agent.name,"threadId",agent.threadId,"projectId",agent.projectId,
            "minecraftAccess",minecraftAccess(agent),"stationId",stationId(agent),"settings",agent.settings,"draft",agent.spawn,"body",agent.body,
            "archived",agent.archived,"removed",agent.removed,"startNonce",agent.startNonce);
    }
    @Override public CompletableFuture<Void> updateSettings(String id,JsonObject settings) {
        return agentRpc("agent.settings",id,object("settings",settings)).thenApply(done -> null);
    }
    /** The body's saved settings; a project agent has no game mode. */
    private void adopt(Agent agent,JsonObject body) {
        for(var entry:body.entrySet()) agent.settings.add(entry.getKey(),entry.getValue());
        settleKind(agent,agent.settings);
    }
    private CompletableFuture<Void> updateBodySettings(Agent agent,JsonObject settings,String session) {
        var physical=BodySettings.copy(settings);
        settleKind(agent,physical);
        validateBody(physical);
        return game.updateSettings(agent.body,session,physical).thenCompose(body -> {
            synchronized(this) {
                requireCurrent(agent,session); agent.body=body;
                adopt(agent,game.settings(body));
                agent.name=text(agent.settings,"name");
            }
            return save();
        });
    }
    @Override public synchronized CompletableFuture<Void> openInventory(String id) { return guarded(() -> game.openInventory(require(id).body,loadedSession,id)); }
    @Override public synchronized CompletableFuture<JsonObject> inventory(String id) { return guarded(() -> game.inventory(require(id).body,loadedSession)); }
    synchronized void bodyLost(GameAccess.Body body) { if(!currentSession(loadedSession)) return; for(var a:agents.values()) if(Objects.equals(a.body,body)) { a.lost=true; closeScopes(a.id,"body_lost"); } save(); }
    @Override public synchronized CompletableFuture<Void> checkBody(String id) { return guarded(() -> {
        var a=require(id); if(!a.lost || a.removed) return CompletableFuture.completedFuture(null);
        String session=loadedSession;
        return game.recoverBody(a.body,a.id,a.settings,session).thenCompose(done -> { synchronized(this) { requireCurrent(a,session); a.lost=false; } return save(); });
    }); }
    CompletableFuture<Void> sendMention(String id,String message,JsonObject pointing) { return send(id,object("text",message,"pointing",pointing,"delivery","steer-if-active")); }
    @Override public CompletableFuture<Void> send(String id,JsonObject message) {
        return agentRpc("agent.message",id,object("message",message)).thenApply(done -> null);
    }
    @Override public CompletableFuture<Void> steerQueued(String id,String messageId) { return agentRpc("agent.queue.steer",id,object("messageId",messageId)).thenApply(done -> null); }
    @Override public CompletableFuture<Void> cancelQueued(String id,String messageId) { return agentRpc("agent.queue.cancel",id,object("messageId",messageId)).thenApply(done -> null); }
    @Override public CompletableFuture<JsonObject> transcript(String id,JsonObject query) { return agentRpc("timeline",id,object("query",query)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> chatAsset(String id,String kind,String source) {
        return agentRpc("chat.asset",id,object("kind",kind,"source",source)).thenApply(JsonElement::getAsJsonObject);
    }
    @Override public CompletableFuture<Void> openChatLink(String id,String target) {
        return agentRpc("chat.open",id,object("target",target)).thenApply(ignored->null);
    }
    CompletableFuture<JsonObject> developmentChatAsset(String threadId,String kind,String source) {
        if(!DevelopmentWorld.ENABLED)return failed("Development only");
        return rpc("chat.asset",object("threadId",threadId,"kind",kind,"source",source)).thenApply(JsonElement::getAsJsonObject);
    }
    CompletableFuture<Void> developmentChatLink(String threadId,String target) {
        if(!DevelopmentWorld.ENABLED)return failed("Development only");
        return rpc("chat.open",object("threadId",threadId,"target",target)).thenApply(ignored->null);
    }
    @Override public CompletableFuture<JsonObject> timelineTurnSummaryDetails(String id,JsonObject query) { return agentRpc("timeline.summary",id,object("query",query)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<Void> respond(String id,String requestId,JsonObject resolution) { return agentRpc("interaction.resolve",id,object("interactionId",requestId,"resolution",resolution)).thenApply(done -> null); }
    @Override public synchronized CompletableFuture<Void> interrupt(String id) { return guarded(() -> {
        var agent=require(id);
        closeScopes(id,"interrupted");
        return agent.threadId.isBlank()?CompletableFuture.completedFuture(null):agentRpc("agent.stop",id,new JsonObject()).thenApply(done -> null);
    }); }
    @Override public synchronized CompletableFuture<Void> archiveConversation(String id,boolean archive) { return guarded(() -> {
        var agent=require(id); String session=loadedSession;
        closeScopes(id,archive?"agent_archived":"agent_restored");
        boolean hasThread=!agent.threadId.isBlank();
        var change=hasThread ? agentRpc("agent.archive",id,object("archived",archive)) : CompletableFuture.completedFuture(JsonNull.INSTANCE);
        return change.thenCompose(done -> {
                synchronized(this) {
                    requireCurrent(agent,session);
                    // Bound agents follow BB; only unstarted bodies need a local choice.
                    agent.localArchive=hasThread?null:archive;
                    return save();
                }
            })
            .thenCompose(done -> applyBodyState(agent,archive?"suspended":"present",false,session)).thenCompose(done -> save());
    }); }
    @Override public synchronized CompletableFuture<Void> remove(String id,boolean archive) { return guarded(() -> {
        var agent=require(id); String session=loadedSession;
        if(archive) return archiveConversation(id,true);
        closeScopes(id,"body_removed");
        if(!agent.suspendedBody.isBlank()) { agent.removed=true; agent.suspendedBody=""; return save().thenCompose(done -> game.releaseStation(agent.id,session)); }
        return removeBody(agent,session,null,0);
    }); }
    private CompletableFuture<Void> removeBody(Agent agent,String session,GameAccess.ToolScope scope,long expiresAt) {
        var removed=scope==null?game.removeAgent(agent.body,session,agent.id,agent.lost):game.removeAgent(agent.body,session,agent.id,agent.lost,scope,expiresAt);
        return removed.thenCompose(done -> { synchronized(this) { requireCurrent(agent,session); agent.removed=true; } return save(); })
            .thenCompose(done -> game.releaseStation(agent.id,session));
    }
    synchronized CompletableFuture<JsonObject> call(String id,String tool,JsonObject arguments) {
        return guarded(() -> {
            var agent=require(id);
            if(tool.startsWith("screen_")) {
                // The same screen call an agent's thread makes, so development checks exercise its access and leases.
                if(agent.archived || agent.deleted || agent.threadId.isBlank()) return failed("This agent has no active conversation.");
                var requestId=UUID.randomUUID().toString();
                var scope=game.newToolScope(loadedSession);
                scopes.put(requestId,scope); scopeAgents.put(requestId,agent.id); screenScopes.add(requestId);
                return game.screen(scope,screenCaller(agent),tool,arguments,Long.MAX_VALUE).whenComplete((done,failure) -> screenFinished(requestId,scope));
            }
            String session=loadedSession;
            return AgentSurface.call(minecraftAccess(agent),game,agent.body,session,null,tool,arguments)
                .thenCompose(result -> rememberBehavior(agent,session,tool,result));
        });
    }

    private CompletableFuture<JsonObject> rememberBehavior(Agent agent,String session,String tool,JsonObject result) {
        if(!tool.equals("minecraft_behavior")) return CompletableFuture.completedFuture(result);
        synchronized(this) {
            requireCurrent(agent,session);
            agent.settings.addProperty("behavior",BodySettings.behavior(text(result,"behavior")));
        }
        return save().thenApply(done -> result);
    }

    private synchronized void screenFinished(String requestId,GameAccess.ToolScope scope) {
        if(scopes.remove(requestId)!=null) scope.close("tool_finished");
        scopeAgents.remove(requestId); screenScopes.remove(requestId);
    }

    /** Who is calling a screen tool. Screens need no body; one that is present gives distances from it. */
    private ScreenTools.Caller screenCaller(Agent agent) {
        var threads=new HashMap<String,String>();
        for(var other:agents.values()) if(!other.deleted && !other.threadId.isBlank()) threads.put(other.id,other.threadId);
        boolean present=!agent.removed && agent.body!=null && game.belongsToCurrentWorld(agent.body);
        return new ScreenTools.Caller(agent.id,agent.threadId,agent.name,agent.projectId,present?UUID.fromString(agent.body.entityUuid()):null,threads);
    }

    static final class StaleSessionException extends IllegalStateException {
        StaleSessionException() { super("world_session_changed"); }
    }

    /** Requests from the BB plugin. Physical requests must come from the agent's own thread. */
    synchronized CompletableFuture<JsonObject> callback(JsonObject request) {
        return callback(request, null);
    }

    synchronized CompletableFuture<JsonObject> callback(JsonObject request, ScriptStream stream) {
        try {
            String session=worldSession.get(), op=text(request,"op"), requestId=text(request,"requestId");
            if(request.get("protocol").getAsInt()!=3) return failed("protocol_mismatch");
            if(session==null || !session.equals(loadedSession) || !session.equals(text(request,"worldSessionId"))
                || !loadedWorldId.equals(text(request,"worldId"))) return CompletableFuture.failedFuture(new StaleSessionException());
            if(BbSetup.get().installing() || !connectionId.equals(text(request,"connectionId"))) return CompletableFuture.failedFuture(new StaleSessionException());
            String instance=text(request,"bbInstanceId");
            // Setup may temporarily be unready while probing connected games. Selection,
            // not setup readiness, determines whether this BB still owns the callback.
            if(instance.isBlank() || !instance.equals(BbSetup.get().selectedInstance())) return CompletableFuture.failedFuture(new StaleSessionException());
            var targetAgent=agents.get(text(request,"agentId"));
            if(targetAgent!=null && !op.equals("body.bind") && !targetAgent.bbInstanceId.equals(instance)) return failed("This agent belongs to another BB.");
            long expiresAt=request.get("expiresAt").getAsLong();
            switch(op) {
                case "body.parent": {
                    var child=require(text(request,"agentId"));
                    if(child.threadId.equals(text(request,"threadId"))) child.parentThreadId=text(request,"parentThreadId");
                    return CompletableFuture.completedFuture(new JsonObject());
                }
                case "agents": {
                    var rows=new JsonArray();
                    for(var a:agents.values()) if(!a.deleted && a.bbInstanceId.equals(instance)) rows.add(bodyRecord(a));
                    return CompletableFuture.completedFuture(object("agents",rows));
                }
                case "body.bind": return bind(text(request,"agentId"),text(request,"threadId"),text(request,"nonce"),session,instance).thenApply(done -> new JsonObject());
                case "body.begin": {
                    var agent=require(text(request,"agentId"));
                    if(agent.removed || agent.archived || !agent.threadId.isBlank()) return failed("This body cannot start a new conversation.");
                    if(!agent.startNonce.isBlank()) return failed("A previous start has an unknown outcome. Inspect BB and reconnect before starting again.");
                    agent.startNonce=text(request,"nonce"); UUID.fromString(agent.startNonce);
                    return save().thenApply(done -> new JsonObject());
                }
                case "body.draft": {
                    var agent=require(text(request,"agentId"));
                    if(!agent.threadId.isBlank()) return failed("This body already has a conversation.");
                    for(var entry:obj(request,"patch").entrySet()) agent.spawn.add(entry.getKey(),entry.getValue());
                    return save().thenApply(done -> new JsonObject());
                }
                case "body.settings": {
                    var agent=require(text(request,"agentId"));
                    return updateBodySettings(agent,obj(request,"settings"),session).thenApply(done -> new JsonObject());
                }
                case "body.sync": {
                    var agent=agents.get(text(request,"agentId"));
                    if(agent==null || !agent.threadId.equals(text(request,"threadId"))) return CompletableFuture.completedFuture(new JsonObject());
                    if(request.has("view")) agent.remote=obj(request,"view").deepCopy();
                    String project=text(request,"projectId");
                    if(!project.isBlank() && !project.equals(agent.projectId)) {
                        agent.projectId=project;
                        settleKind(agent,agent.settings);
                        return game.releaseStation(agent.id,session).thenCompose(done -> save())
                            .thenCompose(done -> applyBodyState(agent,text(request,"state"),flag(request,"running"),session))
                            .thenApply(done -> new JsonObject());
                    }
                    return applyBodyState(agent,text(request,"state"),flag(request,"running"),session).thenApply(done -> new JsonObject());
                }
                case "world.workspace": {
                    Path path=Path.of(text(game.worldInfo(),"directory")).resolve("too-many-agents/workspace");
                    return CompletableFuture.supplyAsync(() -> { try { return object("path",Files.createDirectories(path).toString()); } catch(Exception failure) { throw new CompletionException(failure); } },disk);
                }
                case "world.project": return game.worldCommand(object("operation","world-project","bbInstanceId",instance,"projectId",text(request,"projectId")),session).thenCompose(done -> {
                    synchronized(this) {
                        if(!text(game.worldInfo(),"copiedFrom").isBlank()) for(var agent:agents.values()) {
                            if(!agent.projectId.equals("minecraft")) continue;
                            agent.projectId=text(request,"projectId");
                            agent.spawn.addProperty("projectId",agent.projectId);
                        }
                    }
                    return save().thenApply(saved -> new JsonObject());
                });
                case "communication": {
                    var sender=agents.get(text(request,"senderAgentId"));
                    var recipient=agents.get(text(request,"recipientAgentId"));
                    if(sender==null || recipient==null || sender.removed || recipient.removed || sender.archived || recipient.archived
                        || !sender.bbInstanceId.equals(instance) || !recipient.bbInstanceId.equals(instance)
                        || !sender.threadId.equals(text(request,"senderThreadId")) || !recipient.threadId.equals(text(request,"recipientThreadId")))
                        return CompletableFuture.completedFuture(new JsonObject());
                    String preview=text(request,"message");
                    return game.announceCommunication(sender.body,recipient.body,session,preview).thenApply(done -> new JsonObject());
                }
                case "cancel": {
                    String target=text(request,"cancelRequestId");
                    cancelled.add(target);
                    var scope=scopes.remove(target);
                    if(scope!=null) {
                        var agent=agents.get(scopeAgents.remove(target));
                        var scriptId=scopeScripts.remove(target);
                        scope.close("tool_cancelled");
                        // A screen call has nothing physical to stop, and the body may be busy with other work.
                        if(agent!=null && !screenScopes.remove(target)) game.requestActionStop(agent.body,session,scriptId);
                    }
                    return CompletableFuture.completedFuture(new JsonObject());
                }
                case "world_metadata": return game.worldMetadata(session,expiresAt).thenApply(world -> worldView(world,instance));
                case "screen": {
                    // Screen tools are for every agent, with or without a body or Minecraft access.
                    // Refusals are lifecycle failures, which the agent reads as screen errors.
                    if(expiresAt<System.currentTimeMillis()) return ScreenTools.settle(failed("expired_before_execution"));
                    var agent=require(text(request,"agentId"));
                    if(agent.archived || agent.deleted || agent.threadId.isBlank() || !agent.threadId.equals(text(request,"threadId")))
                        return ScreenTools.settle(failed("conversation_unavailable"));
                    UUID.fromString(requestId);
                    if(cancelled.remove(requestId) || scopes.containsKey(requestId)) return ScreenTools.settle(failed("tool_cancelled"));
                    var scope=game.newToolScope(session);
                    scopes.put(requestId,scope); scopeAgents.put(requestId,agent.id); screenScopes.add(requestId);
                    return game.screen(scope,screenCaller(agent),text(request,"tool"),obj(request,"arguments"),expiresAt)
                        .whenComplete((done,failure) -> screenFinished(requestId,scope));
                }
            }
            if(expiresAt<System.currentTimeMillis()) return failed("expired_before_execution");
            String actor=text(request,"agentId");
            Agent agent=actor.isBlank()?null:require(actor);
            if(agent==null && !op.equals("body.create")) return failed("An acting body is required.");
            if(agent!=null) {
                if(agent.removed || agent.archived || !game.belongsToCurrentWorld(agent.body)) return failed("agent_body_unavailable");
                if(agent.threadId.isBlank() || !agent.threadId.equals(text(request,"threadId"))) return failed("This thread is not the Minecraft agent's conversation.");
            }
            UUID.fromString(requestId);
            if(cancelled.remove(requestId) || scopes.containsKey(requestId)) return failed("tool_cancelled");
            var scope=game.newToolScope(session);
            scopes.put(requestId,scope); scopeAgents.put(requestId,actor);
            if(op.equals("script")) scopeScripts.put(requestId,text(obj(request,"arguments"),"scriptId"));
            CompletableFuture<JsonObject> work=switch(op) {
                case "tool" -> game.callInTurn(scope,agent.body,AgentSurface.operation(minecraftAccess(agent),text(request,"tool")),obj(request,"arguments"),expiresAt)
                    .thenCompose(result -> rememberBehavior(agent,session,text(request,"tool"),result)).thenApply(AgentService::toolResult);
                case "script" -> game.callInTurn(scope,agent.body,AgentSurface.operation(minecraftAccess(agent),"minecraft_run"),obj(request,"arguments"),expiresAt,stream);
                case "body.create" -> createBody(request,agent,session,scope,expiresAt).thenApply(created -> { synchronized(this) { return bodyRecord(created); } });
                default -> failed("unknown_callback_operation");
            };
            return work.whenComplete((done,failure) -> { synchronized(this) { if(scopes.remove(requestId)!=null) scope.close("tool_finished"); scopeAgents.remove(requestId); scopeScripts.remove(requestId); } });
        } catch(Exception failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static void validateBody(JsonObject settings) {
        for(var entry:settings.entrySet()) {
            var value=entry.getValue();
            boolean valid=switch(entry.getKey()) {
                case "name" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case "body" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case "mode" -> {
                    if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) yield false;
                    BodySettings.mode(value.getAsString());
                    yield true;
                }
                case "behavior" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && Set.of("stand","wander","follow").contains(value.getAsString());
                case "behaviors" -> value.isJsonObject();
                default -> false;
            };
            if(!valid) throw new IllegalArgumentException("Invalid body setting: "+entry.getKey());
        }
        if(settings.has("behaviors")) validateBehaviors(settings.get("behaviors"));
    }
    private static JsonObject toolResult(JsonObject result) {
        var content=new JsonArray();
        var copy=result.deepCopy(); String image=text(copy,"imageDataUrl"); copy.remove("imageDataUrl");
        content.add(object("type","text","text",copy.toString()));
        if(image.startsWith("data:image/png;base64,")) content.add(object("type","image","mimeType","image/png","data",image.substring("data:image/png;base64,".length())));
        return object("content",content);
    }
    private synchronized void closeScopes(String agentId,String reason) {
        var keys=scopes.keySet().stream().filter(key -> agentId==null || agentId.equals(scopeAgents.get(key))).toList();
        for(var key:keys) { scopes.remove(key).close(reason); scopeAgents.remove(key); scopeScripts.remove(key); screenScopes.remove(key); }
        for(var agent:agents.values()) if(agentId==null || agentId.equals(agent.id)) game.requestActionStop(agent.body,loadedSession);
        // A thread that stops, is archived or loses its connection gives up its screens at once.
        var holder=agentId==null?null:agents.get(agentId);
        if(agentId==null) game.releaseScreenLeases(null,loadedSession);
        else if(holder!=null && !holder.threadId.isBlank()) game.releaseScreenLeases(holder.threadId,loadedSession);
    }
    synchronized void disconnectBb() {
        connectionId=UUID.randomUUID().toString();
        closeScopes(null,"bb_disconnected");
        connected=false; connectedInstance=""; projectRows(new JsonArray());
    }
    synchronized void worldClosed(String session) {
        closeScopes(null,"world_session_changed"); connected=false;
        if(session.equals(loadedSession)) {
            loadedSession=null;
            bb.call(object("op","session.detach","connectionId",connectionId,"worldId",loadedWorldId,"worldSessionId",session)).exceptionally(failure -> null);
        }
    }
    private static String message(Throwable error) { while((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause()!=null) error=error.getCause(); return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); }
    /** Reports a synchronous failure (e.g. a stale world session) as a failed future, so UI handlers never see it thrown. */
    private static <T> CompletableFuture<T> guarded(Supplier<CompletableFuture<T>> body) {
        try { return body.get(); } catch(RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static <T> CompletableFuture<T> failed(String message) { return CompletableFuture.failedFuture(new IllegalStateException(message)); }
    @Override public void close() {
        synchronized(this) { closed=true; closeScopes(null,"minecraft_closed"); }
        polling.shutdownNow(); disk.shutdown(); bb.close();
    }
    /** Per status, one of stand, wander, follow, jump, spin, or look at the player. */
    static void validateBehaviors(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Behaviors must be an object keyed by state.");
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!Set.of("working","wants_you","idle").contains(entry.getKey())) throw new IllegalArgumentException("Unknown behavior state: " + entry.getKey());
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Each behavior must be an object.");
            var behavior = entry.getValue().getAsJsonObject();
            var target = behavior.get("target");
            boolean valid = switch (text(behavior,"type")) {
                case "stand", "wander", "follow", "jump", "spin" -> target == null;
                case "look" -> target == null || target.isJsonPrimitive() && target.getAsString().equals("player");
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid " + entry.getKey() + " behavior: use stand, wander, follow, jump, spin, or look at the player.");
        }
    }
}
