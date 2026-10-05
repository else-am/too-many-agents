package toomanyagents;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import toomanyagents.ui.AgentUiAccess;
import static toomanyagents.JsonState.*;

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
    // A cancel can arrive before the call it cancels.
    private final Set<String> cancelled = new HashSet<>();

    private JsonArray projectRows = new JsonArray();
    private Path records;
    private String loadedSession, loadedWorldId, callbackUrl, callbackToken, error = "";
    private boolean connected, closed;

    private static final class Agent {
        String id, name, projectId="", threadId="", parentThreadId="", suspendedBody="", startNonce="";
        GameAccess.Body body;
        JsonObject settings=new JsonObject();
        // Opaque plugin-owned draft, retained with an unstarted body.
        JsonObject spawn=new JsonObject();
        // The plugin's latest UI view; Java does not derive conversation state.
        JsonObject remote=new JsonObject();
        boolean minecraftAccess=true, removed, lost, archived, deleted, suspended;
    }

    AgentService(GameAccess game, Supplier<String> worldSession) {
        this.game=game; this.worldSession=worldSession; bb=new BbClient();
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
        try {
            synchronized(this) {
                if(!session.equals(worldSession.get())) return;
                if(!session.equals(loadedSession)) loadWorld(world,session);
            }
            bb.call(object("op","session.attach","protocol",3,"worldId",worldId,"worldSessionId",session,"callbackUrl",callbackUrl,"callbackToken",callbackToken)).join();
            synchronized(this) { if(!currentSession(session)) return; }
            var state=rpc("world.sync",new JsonObject()).join().getAsJsonObject();
            synchronized(this) { if(!currentSession(session)) return; projectRows=array(state,"projects"); connected=true; error=""; }

        } catch(Exception failure) {
            synchronized(this) { if(currentSession(session)) { connected=false; error=message(failure); } }
        }
    }

    /** Apply the physical state requested by the plugin, including after reconnect. */
    private CompletableFuture<Void> applyBodyState(Agent agent,String state,boolean running,String session) {
        synchronized(this) {
            requireCurrent(agent,session);
            if(state.equals("deleted")) {
                closeScopes(agent.id,"thread_deleted");
                agent.removed=true; agent.deleted=true; agent.threadId=""; agent.spawn=new JsonObject();
                agent.suspendedBody="";
                return save().thenCompose(done -> game.worldCommand(object("operation","station-release","agentId",agent.id),session)).thenApply(done -> null);
            }
            if(state.isBlank()) return CompletableFuture.completedFuture(null);
            if(!Set.of("present","suspended").contains(state)) return failed("unknown_body_state");
            if(!running) closeScopes(agent.id,"thread_not_active");
            boolean archived=state.equals("suspended");
            agent.archived=archived;
            if(agent.removed) return CompletableFuture.completedFuture(null);
            if(archived) {
                if(agent.suspended) return CompletableFuture.completedFuture(null);
                var captured=agent.suspendedBody.isBlank()?game.saveBody(agent.body,session):CompletableFuture.completedFuture(agent.suspendedBody);
                return captured.thenCompose(saved -> {
                    synchronized(this) { requireCurrent(agent,session); agent.suspendedBody=saved; agent.settings.remove("stationId"); }
                    // Inventory has been dropped; persist the empty body before despawning it.
                    return save();
                }).thenCompose(done -> game.suspendBody(agent.body,agent.suspendedBody,session))
                    .thenCompose(done -> game.worldCommand(object("operation","station-release","agentId",agent.id),session))
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
        agents.clear(); cancelled.clear(); connected=false;
        String worldId=text(world,"id");
        records=Path.of(text(world,"directory")).resolve("too-many-agents/bb-bodies.json");
        if(Files.exists(records)) {
            var saved=JsonParser.parseString(Files.readString(records)).getAsJsonObject();
            if(!text(saved,"worldId").equals(worldId)) throw new IllegalStateException("Saved bodies belong to a different world identity.");
            for(var value:array(saved,"agents")) loadAgent(value.getAsJsonObject(),worldId);
        }
        loadedSession=session; loadedWorldId=worldId;
        save();
    }
    private void loadAgent(JsonObject row,String worldId) {
        var agent=new Agent(); agent.id=text(row,"id"); agent.name=text(row,"name");
        UUID.fromString(agent.id);
        agent.body=JSON.fromJson(row.get("body"),GameAccess.Body.class);
        if(agent.body!=null) agent.body=new GameAccess.Body(agent.body.entityUuid(),worldId,agent.body.dimension());
        agent.settings=BodySettings.copy(obj(row,"settings")); agent.minecraftAccess=flag(row,"minecraftAccess");
        agent.projectId=text(row,"projectId"); agent.threadId=text(row,"threadId"); agent.suspendedBody=text(row,"suspendedBody"); agent.deleted=flag(row,"deleted");
        agent.startNonce=text(row,"startNonce");
        agent.spawn=obj(row,"spawn").deepCopy(); agent.archived=flag(row,"archived");
        agent.removed=flag(row,"bodyRemoved"); agent.lost=flag(row,"bodyLost");
        agents.put(agent.id,agent);
    }
    private synchronized CompletableFuture<Void> save() {
        if(records==null) return CompletableFuture.completedFuture(null);
        var rows=new JsonArray();
        for(var agent:agents.values()) rows.add(object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,
            "minecraftAccess",agent.minecraftAccess,"projectId",agent.projectId,"threadId",agent.threadId,"suspendedBody",agent.suspendedBody,"deleted",agent.deleted,
            "startNonce",agent.startNonce,"spawn",agent.spawn,"archived",agent.archived,"bodyRemoved",agent.removed,"bodyLost",agent.lost));
        var data=object("worldId",loadedWorldId,"agents",rows); var file=records;
        return CompletableFuture.runAsync(() -> { try { JsonState.write(file,data); } catch(Exception failure) { throw new CompletionException(failure); } },disk);
    }
    private synchronized CompletableFuture<JsonElement> rpc(String op,JsonObject arguments) {
        var request=arguments.deepCopy(); request.addProperty("op",op);
        request.addProperty("worldId",loadedWorldId); request.addProperty("worldSessionId",loadedSession);
        return bb.call(request);
    }
    private CompletableFuture<JsonElement> agentRpc(String op,String id,JsonObject arguments) {
        var request=arguments.deepCopy(); request.addProperty("agentId",id);
        return rpc(op,request);
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
        // Keep drafts and pending starts, too. Only BB can confirm a bound thread's deletion.
        return agents.values().stream().filter(a -> !a.removed).map(a -> a.id).collect(Collectors.toSet());
    }
    synchronized Map<String,GameAccess.AgentState> agentStates() {
        var result=new HashMap<String,GameAccess.AgentState>();
        if(!currentSession(loadedSession)) return result;
        for(var agent:agents.values()) if(!agent.removed && !agent.archived) {
            String activity=text(snapshot(agent.id),"activity");
            result.put(agent.id,new GameAccess.AgentState(agent.projectId,activity));
        }
        return result;
    }
    @Override public synchronized JsonArray list() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) if(!agent.deleted) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonArray worldAgents() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) if(!agent.removed && !agent.archived && game.belongsToCurrentWorld(agent.body)) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonObject snapshot(String id) {
        var agent=agents.get(id); if(agent==null || !currentSession(loadedSession)) return object("id",id,"status","unavailable");
        String agentError=text(agent.remote,"error");
        var state=agent.remote.deepCopy();
        var physical=object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,"projectId",agent.projectId,
            "threadId",agent.threadId,"lifecycle",agent.removed?"removed":"active","bodyRemoved",agent.removed,"bodyLost",agent.lost,
            "minecraftAccess",agent.minecraftAccess,"currentWorld",game.belongsToCurrentWorld(agent.body),
            "bodyType",text(agent.settings,"body"),"gamePaused",game.isPaused(),"conversationArchived",agent.archived);
        for(var entry:physical.entrySet()) state.add(entry.getKey(),entry.getValue());
        if(agent.threadId.isBlank()) { state.add("executionOptions",agent.spawn); state.addProperty("providerId",text(agent.spawn,"providerId")); }
        if(!state.has("status")) state.addProperty("status","idle");
        if(!state.has("activity")) state.addProperty("activity","idle");
        if(!connected || !agentError.isBlank()) { state.addProperty("status","disconnected"); state.addProperty("canSteer",false); }
        var live=agent.body==null?new JsonObject():game.cached(agent.body);
        state.addProperty("bodyLoaded",!live.isEmpty());
        if(live.has("action")) state.add("action",live.get("action"));
        int pending=scopes.entrySet().stream().filter(e -> agent.id.equals(scopeAgents.get(e.getKey()))).mapToInt(e -> e.getValue().pendingCount()).sum();
        state.addProperty("pendingWorldTools",pending); state.addProperty("waitingForGame",game.isPaused() && pending>0);
        if(!agentError.isBlank()) state.addProperty("error",agentError);
        else if(!error.isBlank()) state.addProperty("error",error);
        return state;
    }
    @Override public synchronized void markRead(String id,long version) { if(currentSession(loadedSession) && connected && !require(id).threadId.isBlank()) agentRpc("agent.markRead",id,new JsonObject()).exceptionally(f -> null); }
    @Override public CompletableFuture<JsonObject> backendStatus() { return rpc("backend.status",new JsonObject()).thenApply(value -> { var status=value.getAsJsonObject(); status.addProperty("connected",connected); status.addProperty("error",error); return status; }).exceptionally(failure -> object("connected",false,"error",message(failure))); }
    @Override public CompletableFuture<JsonObject> backendConfig() { return rpc("system.config",new JsonObject()).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> setDefaultProvider(String providerId) { return rpc("system.defaultProvider.set",object("providerId",providerId)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> projectExecutionOptions(String projectId) {
        return rpc("project.executionOptions",object("projectId",projectId)).thenApply(value -> value.isJsonObject()?value.getAsJsonObject():new JsonObject());
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
    @Override public synchronized JsonObject projects() { return object("projects",projectRows,"world",game.worldInfo()); }
    @Override public CompletableFuture<JsonObject> projectCommand(JsonObject request) { return guarded(() -> {
        String operation=text(request,"operation");
        if(operation.equals("world-resolve") || operation.startsWith("bounds-") || operation.startsWith("station-")) {
            var copy=request.deepCopy(); if(!text(copy,"agentId").isBlank()) copy.addProperty("agentProjectId",require(text(copy,"agentId")).projectId);
            return game.worldCommand(copy,worldSession.get());
        }
        CompletableFuture<JsonElement> response;
        if(operation.equals("remove")) {
            JsonObject removal;
            synchronized(this) {
                var world=game.worldInfo(); // Serialized snapshot published by the server thread.
                if(!currentSession(loadedSession) || !Objects.equals(loadedWorldId,text(world,"id"))
                    || !Objects.equals(loadedWorldId,game.currentWorldId())) throw new IllegalStateException("world_session_changed");
                String project=text(request,"projectId");
                var remaining=new ArrayList<String>();
                var bodies=agents.values().stream().filter(a -> !a.removed && a.projectId.equals(project)).map(a -> a.name).toList();
                if(!bodies.isEmpty()) remaining.add("remove bodies ("+String.join(", ",bodies)+")");
                var stations=new ArrayList<String>();
                for(var value:array(world,"stations")) {
                    var station=value.getAsJsonObject();
                    if(text(station,"projectId").equals(project)) stations.add(text(station,"label"));
                }
                if(!stations.isEmpty()) remaining.add("unassign and delete stations ("+String.join(", ",stations)+")");
                for(var value:array(world,"bounds")) if(text(value.getAsJsonObject(),"projectId").equals(project)) {
                    remaining.add("clear the project box"); break;
                }
                if(!remaining.isEmpty()) throw new IllegalStateException("Cannot remove project. First "+String.join("; ",remaining)+".");
                removal=request.deepCopy(); removal.addProperty("op","project.remove");
                removal.addProperty("worldId",loadedWorldId); removal.addProperty("worldSessionId",loadedSession);
            }
            // BB owns deletion. Do not hold the body lock while contacting it.
            response=bb.call(removal);
        } else response=rpc("project."+operation,request);
        return response.thenApply(JsonElement::getAsJsonObject).whenComplete((done,failure) -> polling.execute(this::sync));
    }); }
    @Override public CompletableFuture<JsonArray> roles() {
        return rpc("role.list",new JsonObject()).thenApply(JsonElement::getAsJsonArray);
    }
    @Override public CompletableFuture<Void> saveRole(JsonObject role,String agentId) {
        var request=object("role",role);
        if(agentId!=null) request.addProperty("agentId",agentId);
        return rpc("role.save",request).thenApply(done -> null);
    }

    @Override public CompletableFuture<String> spawn(JsonObject request) {
        return rpc("agent.create",object("request",request)).thenApply(result -> text(result.getAsJsonObject(),"agentId"));
    }

    /** Creates and saves a physical body. BB choices are opaque draft data owned by the plugin. */
    private CompletableFuture<Agent> createBody(JsonObject request,Agent caller,String session,GameAccess.ToolScope scope,long expiresAt) {
        var requested=obj(request,"settings");
        var settings=caller==null?BodySettings.copy(requested):BodySettings.copy(caller.settings);
        if(caller!=null) {
            settings.remove("name"); settings.remove("stationId");
            for(var entry:BodySettings.copy(requested).entrySet()) settings.add(entry.getKey(),entry.getValue());
            var mode=BodySettings.mode(text(settings,"mode"));
            var callerMode=BodySettings.mode(text(caller.settings,"mode"));
            if(mode.commands && !callerMode.commands) return failed("A body cannot gain world commands that its caller lacks.");
            if(mode.creative && !callerMode.creative) return failed("A Survival caller cannot give another body Creative access.");
        }
        String id=UUID.randomUUID().toString(), projectId=text(request,"projectId");
        if(caller!=null && !caller.projectId.equals(projectId)) return failed("An agent can spawn only in its own body's project.");
        synchronized(this) {
            if(!currentSession(session)) return failed("world_session_changed");
            if(text(settings,"name").isBlank()) settings.addProperty("name",StarterAgents.name(agents.values().stream().map(a -> a.name).collect(Collectors.toSet())));
            if(text(settings,"body").isBlank()) settings.addProperty("body",StarterAgents.body());
        }
        if(!settings.has("mode")) settings.addProperty("mode","survival");
        validateRoleBody(BodySettings.profile(settings));
        String name=text(settings,"name");
        var agent=new Agent(); agent.id=id; agent.name=name; agent.projectId=projectId;
        // World drafts always have access; delegated bodies still inherit their caller's access.
        agent.minecraftAccess=caller==null
            ? projectId.equals(text(game.worldInfo(),"worldProjectId")) || flag(request,"minecraftAccess")
            : caller.minecraftAccess && (!requested.has("minecraftAccess") || flag(requested,"minecraftAccess"));
        settings.addProperty("minecraftAccess", agent.minecraftAccess);
        agent.settings=settings; agent.spawn=obj(request,"draft").deepCopy();
        var anchor=caller==null?null:caller.body;
        String stationId=text(settings,"stationId");
        var assigned=stationId.isBlank()?CompletableFuture.completedFuture(new JsonObject()):game.worldCommand(object("operation","station-assign","stationId",stationId,"agentId",id,"agentProjectId",projectId),session,scope,expiresAt);
        return assigned.thenCompose(done -> anchor==null
            ? game.spawn(name,text(settings,"body"),id,projectId,session,expiresAt)
            : game.spawnNear(name,text(settings,"body"),id,projectId,anchor,session,scope,expiresAt)
        ).thenCompose(body -> {
            agent.body=body;
            synchronized(this) {
                if(!Objects.equals(session,loadedSession) || !Objects.equals(session,worldSession.get())) return failed("world_session_changed");
                agents.put(id,agent);
            }
            // Save at once: a later failure must never leave a created body anonymous.
            return save().thenCompose(saved -> game.updateSettings(body,session,settings));
        }).thenCompose(body -> {
            synchronized(this) { requireCurrent(agent,session); agent.body=body; for(var entry:game.settings(body).entrySet()) agent.settings.add(entry.getKey(),entry.getValue()); }
            return save().thenApply(done -> agent);
        }).exceptionallyCompose(failure -> {
            // Release a reservation only when no body was created; never retry an uncertain spawn.
            if(agent.body!=null || stationId.isBlank()) return CompletableFuture.failedFuture(failure);
            return game.worldCommand(object("operation","station-release","agentId",id),session).handle((done,releaseFailure) -> {
                if(releaseFailure!=null) failure.addSuppressed(releaseFailure);
                throw new CompletionException(failure);
            });
        });
    }
    private CompletableFuture<Void> bind(String id,String threadId,String nonce,String session) {
        var agent=agents.get(id);
        if(!currentSession(session) || agent==null || !agent.threadId.isBlank() || nonce.isBlank() || !agent.startNonce.equals(nonce)) return CompletableFuture.completedFuture(null);
        agent.threadId=threadId; agent.startNonce=""; agent.spawn=new JsonObject();
        return save();
    }
    private JsonObject bodyRecord(Agent agent) {
        return object("agentId",agent.id,"name",agent.name,"threadId",agent.threadId,"projectId",agent.projectId,
            "minecraftAccess",agent.minecraftAccess,"settings",agent.settings,"draft",agent.spawn,"body",agent.body,
            "archived",agent.archived,"removed",agent.removed,"startNonce",agent.startNonce);
    }
    @Override public CompletableFuture<Void> updateSettings(String id,JsonObject settings) {
        return agentRpc("agent.settings",id,object("settings",settings)).thenApply(done -> null);
    }
    private CompletableFuture<Void> updateBodySettings(Agent agent,JsonObject settings,String session) {
        var physical=BodySettings.copy(settings);
        if(physical.has("behaviors")) validateBehaviors(physical.get("behaviors"));
        return game.updateSettings(agent.body,session,physical).thenCompose(body -> {
            synchronized(this) {
                requireCurrent(agent,session); agent.body=body;
                for(var entry:game.settings(body).entrySet()) agent.settings.add(entry.getKey(),entry.getValue());
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
        return game.recoverBody(a.body,a.id,a.projectId,a.settings,session).thenCompose(done -> { synchronized(this) { requireCurrent(a,session); a.lost=false; } return save(); });
    }); }
    CompletableFuture<Void> sendMention(String id,String message,JsonObject pointing) { return send(id,object("text",message,"pointing",pointing)); }
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
    @Override public CompletableFuture<Void> archiveConversation(String id,boolean archive) {
        return agentRpc("agent.archive",id,object("archived",archive)).thenApply(done -> null);
    }
    @Override public synchronized CompletableFuture<Void> remove(String id,boolean archive) { return guarded(() -> {
        var agent=require(id); String session=loadedSession;
        if(archive) return archiveConversation(id,true);
        closeScopes(id,"body_removed");
        if(!agent.suspendedBody.isBlank()) { agent.removed=true; agent.suspendedBody=""; return save().thenCompose(done -> game.worldCommand(object("operation","station-release","agentId",agent.id),session)).thenApply(done -> null); }
        return removeBody(agent,session,null,0);
    }); }
    private CompletableFuture<Void> removeBody(Agent agent,String session,GameAccess.ToolScope scope,long expiresAt) {
        var removed=scope==null?game.removeAgent(agent.body,session,agent.id,agent.lost):game.removeAgent(agent.body,session,agent.id,agent.lost,scope,expiresAt);
        return removed.thenCompose(done -> { synchronized(this) { requireCurrent(agent,session); agent.removed=true; } return save(); })
            .thenCompose(done -> game.worldCommand(object("operation","station-release","agentId",agent.id),session)).thenApply(done -> null);
    }
    synchronized CompletableFuture<JsonObject> call(String id,String tool,JsonObject arguments) {
        return guarded(() -> { var agent=require(id); return AgentSurface.call(agent.minecraftAccess,game,agent.body,loadedSession,null,tool,arguments); });
    }

    /** Requests from the BB plugin. Physical requests must come from the agent's own thread. */
    synchronized CompletableFuture<JsonObject> callback(JsonObject request) {
        try {
            String session=worldSession.get(), op=text(request,"op"), requestId=text(request,"requestId");
            if(request.get("protocol").getAsInt()!=3 || session==null || !session.equals(loadedSession) || !session.equals(text(request,"worldSessionId"))
                || !loadedWorldId.equals(text(request,"worldId"))) return failed("world_session_changed");
            long expiresAt=request.get("expiresAt").getAsLong();
            switch(op) {
                case "body.validate": {
                    var settings=obj(request,"settings");
                    validateRoleBody(settings);
                    return game.bodies().thenApply(bodies -> {
                        if(settings.has("body") && java.util.stream.StreamSupport.stream(bodies.spliterator(),false)
                            .noneMatch(body -> text(body.getAsJsonObject(),"id").equals(text(settings,"body"))))
                            throw new IllegalArgumentException("Unknown or unspawnable body type.");
                        return new JsonObject();
                    });
                }
                case "body.parent": {
                    var child=require(text(request,"agentId"));
                    if(child.threadId.equals(text(request,"threadId"))) child.parentThreadId=text(request,"parentThreadId");
                    return CompletableFuture.completedFuture(new JsonObject());
                }
                case "agents": {
                    var rows=new JsonArray();
                    for(var a:agents.values()) if(!a.deleted) rows.add(bodyRecord(a));
                    return CompletableFuture.completedFuture(object("agents",rows));
                }
                case "body.bind": return bind(text(request,"agentId"),text(request,"threadId"),text(request,"nonce"),session).thenApply(done -> new JsonObject());
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
                    if(!project.isBlank() && !project.equals(agent.projectId)) { agent.projectId=project; save(); }
                    return applyBodyState(agent,text(request,"state"),flag(request,"running"),session).thenApply(done -> new JsonObject());
                }
                case "world.workspace": {
                    Path path=Path.of(text(game.worldInfo(),"directory")).resolve("too-many-agents/workspace");
                    return CompletableFuture.supplyAsync(() -> { try { return object("path",Files.createDirectories(path).toString()); } catch(Exception failure) { throw new CompletionException(failure); } },disk);
                }
                case "world.project": return game.worldCommand(object("operation","world-project","projectId",text(request,"projectId")),session).thenApply(done -> new JsonObject());
                case "communication": {
                    var sender=agents.get(text(request,"senderAgentId"));
                    var recipient=agents.get(text(request,"recipientAgentId"));
                    if(sender==null || recipient==null || sender.removed || recipient.removed || sender.archived || recipient.archived
                        || !sender.threadId.equals(text(request,"senderThreadId")) || !recipient.threadId.equals(text(request,"recipientThreadId")))
                        return CompletableFuture.completedFuture(new JsonObject());
                    String preview=text(request,"message");
                    return game.announceCommunication(sender.body,recipient.body,session,preview).thenApply(done -> new JsonObject());
                }
                case "cancel": {
                    String target=text(request,"cancelRequestId");
                    cancelled.add(target);
                    var scope=scopes.remove(target);
                    if(scope!=null) { scope.close("tool_cancelled"); var agent=agents.get(scopeAgents.remove(target)); if(agent!=null) game.requestActionStop(agent.body,session); }
                    return CompletableFuture.completedFuture(new JsonObject());
                }
                case "world_metadata": return CompletableFuture.completedFuture(game.worldInfo());
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
            CompletableFuture<JsonObject> work=switch(op) {
                case "station.edit" -> editStation(agent,obj(request,"request"),session,scope,expiresAt);
                case "tool" -> game.callInTurn(scope,agent.body,AgentSurface.operation(agent.minecraftAccess,text(request,"tool")),obj(request,"arguments"),expiresAt).thenApply(AgentService::toolResult);
                case "body.create" -> createBody(request,agent,session,scope,expiresAt).thenApply(created -> { synchronized(this) { return bodyRecord(created); } });
                default -> failed("unknown_callback_operation");
            };
            return work.whenComplete((done,failure) -> { synchronized(this) { if(scopes.remove(requestId)!=null) scope.close("tool_finished"); scopeAgents.remove(requestId); } });
        } catch(Exception failure) { return CompletableFuture.failedFuture(failure); }
    }
    private CompletableFuture<JsonObject> editStation(Agent caller,JsonObject request,String session,GameAccess.ToolScope scope,long expiresAt) {
        String operation=text(request,"operation");
        if(!Set.of("station-create","station-update","station-assign","station-delete").contains(operation)) return failed("Unknown station edit.");
        var command=request.deepCopy();
        command.addProperty("callerProjectId",caller.projectId);
        command.addProperty("projectId",caller.projectId);
        command.addProperty("dimension",text(request,"dimension").isBlank()?caller.body.dimension():text(request,"dimension"));
        var owned=new JsonArray(); owned.add(caller.id);
        for(var child:agents.values())
            if(!child.removed && !child.archived && child.projectId.equals(caller.projectId)
                && !child.threadId.isBlank() && child.parentThreadId.equals(caller.threadId)) owned.add(child.id);
        command.add("ownedAgents",owned);
        if(operation.equals("station-assign")) {
            if(!command.has("agentId")) command.addProperty("agentId",caller.id);
            String target=text(command,"agentId");
            if(!target.isBlank()) {
                if(!owned.contains(new JsonPrimitive(target))) return failed("Assign only your own body or a child body.");
                command.addProperty("agentProjectId",require(target).projectId);
            }
        }
        return game.worldCommand(command,session,scope,expiresAt);
    }
    private static void validateRoleBody(JsonObject settings) {
        for(var entry:settings.entrySet()) {
            String key=entry.getKey(); var value=entry.getValue();
            boolean valid=switch(key) {
                case "body" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && !value.getAsString().isBlank();
                case "mode" -> {
                    if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) yield false;
                    BodySettings.mode(value.getAsString());
                    yield true;
                }
                case "minecraftAccess" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "behaviors" -> value.isJsonObject();
                default -> false;
            };
            if(!valid) throw new IllegalArgumentException("Invalid role body field: "+key);
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
        for(var key:keys) { scopes.remove(key).close(reason); scopeAgents.remove(key); }
        for(var agent:agents.values()) if(agentId==null || agentId.equals(agent.id)) game.requestActionStop(agent.body,loadedSession);
    }
    synchronized void worldClosed(String session) {
        closeScopes(null,"world_session_changed"); connected=false;
        if(session.equals(loadedSession)) {
            loadedSession=null;
            bb.call(object("op","session.detach","worldId",loadedWorldId,"worldSessionId",session)).exceptionally(failure -> null);
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
    static void validateBehaviors(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Behaviors must be an object keyed by state.");
        for (var entry : value.getAsJsonObject().entrySet()) {
            if (!Set.of("working","wants_you","idle").contains(entry.getKey())) throw new IllegalArgumentException("Unknown behavior state: " + entry.getKey());
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
                case "stand", "wander", "follow", "jump", "spin" -> target == null;
                case "look" -> block || player;
                case "swing" -> block;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid " + entry.getKey() + " behavior: use stand, wander, follow, jump, spin, look at the player or a block, or swing at a block.");
        }
    }
}
