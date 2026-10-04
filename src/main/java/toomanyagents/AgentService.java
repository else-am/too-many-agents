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
    private static final Set<String> ACTIVE = Set.of("active","pending","starting","stopping");
    private static final List<String> SPAWN_FIELDS = List.of("projectId","providerId","title","model","reasoningLevel","serviceTier","permissionMode","environment");
    private final Path directory;
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
    private final Map<String,JsonObject> profiles = new LinkedHashMap<>();
    private JsonArray projectRows = new JsonArray();
    // Creates this world's project once, on first use.
    private CompletableFuture<String> worldProject;
    private Path records;
    private String loadedSession, loadedWorldId, callbackUrl, callbackToken, error = "";
    private boolean connected, closed;

    private static final class Agent {
        String id, name, projectId="", threadId="", parentId="";
        GameAccess.Body body;
        JsonObject settings=new JsonObject();
        // BB selection for an agent whose conversation has not started yet.
        JsonObject spawn=new JsonObject();
        // The latest BB thread read: thread, executionOptions, interactions, queuedMessages.
        JsonObject remote=new JsonObject();
        boolean minecraftAccess=true, removed, lost, archived, starting;
    }

    AgentService(Path directory, GameAccess game, Supplier<String> worldSession) {
        this.directory=directory; this.game=game; this.worldSession=worldSession; bb=new BbClient();
        profiles.put("Survival",object("mode","survival","cheats",false,"following",false));
        profiles.put("Creative",object("mode","creative","cheats",false,"following",false));
        try {
            var file=directory.resolve("body-profiles-v1.json");
            if(Files.exists(file)) {
                for(var entry:JsonParser.parseString(Files.readString(file)).getAsJsonObject().entrySet()) profiles.put(entry.getKey(),BodySettings.profile(entry.getValue().getAsJsonObject()));
                JsonState.write(file,JSON.toJsonTree(profiles).getAsJsonObject());
            }
        } catch(Exception failure) { error="Could not read body profiles: "+message(failure); }
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
            bb.call(object("op","session.attach","worldId",worldId,"worldSessionId",session,"callbackUrl",callbackUrl,"callbackToken",callbackToken)).join();
            synchronized(this) { if(!currentSession(session)) return; }
            var listed=bb.call(object("op","projects")).join().getAsJsonArray();
            // BB's built-in Personal project has no folder or place; in Minecraft it means no project, listed last as in BB.
            var projects=new JsonArray(); JsonObject none=null;
            for(var row:listed) {
                var project=row.getAsJsonObject();
                if(text(project,"kind").equals("personal")) { none=project; project.addProperty("name","No project"); }
                else projects.add(project);
            }
            if(none!=null) projects.add(none);
            synchronized(this) { if(!currentSession(session)) return; }
            relocateWorldProject(projects,world);
            synchronized(this) { if(!currentSession(session)) return; projectRows=projects; connected=true; error=""; }
            read(threadIds(),session);
        } catch(Exception failure) {
            synchronized(this) { if(currentSession(session)) { connected=false; error=message(failure); } }
        }
    }

    /** Read these BB threads and keep the results. */
    private void read(List<String> threadIds,String session) {
        if(threadIds.isEmpty()) return;
        var ids=new JsonArray(); threadIds.forEach(ids::add);
        var rows=bb.call(object("op","threads.read","threadIds",ids)).join().getAsJsonObject();
        synchronized(this) {
            if(!currentSession(session)) return;
            for(var agent:agents.values()) if(rows.has(agent.threadId)) {
                agent.remote=rows.getAsJsonObject(agent.threadId);
                String project=text(obj(agent.remote,"thread"),"projectId");
                if(!project.isBlank() && !project.equals(agent.projectId)) { agent.projectId=project; save(); }
            }
        }
    }
    private void refresh(String id) {
        String thread, session;
        synchronized(this) { var agent=agents.get(id); thread=agent==null?"":agent.threadId; session=loadedSession; }
        if(thread.isBlank()) return;
        polling.execute(() -> { try { read(List.of(thread),session); } catch(Exception failure) { synchronized(this) { if(currentSession(session)) error=message(failure); } } });
    }
    private synchronized List<String> threadIds() {
        return agents.values().stream().filter(a -> !a.removed && !a.threadId.isBlank()).map(a -> a.threadId).toList();
    }

    private void loadWorld(JsonObject world,String session) throws Exception {
        closeScopes(null,"world_session_changed");
        agents.clear(); cancelled.clear(); connected=false; worldProject=null;
        String worldId=text(world,"id");
        records=Path.of(text(world,"directory")).resolve("too-many-agents/bb-bodies-v1.json");
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
        agent.settings=BodySettings.copy(obj(row,"settings")); agent.minecraftAccess=!row.has("minecraftAccess") || flag(row,"minecraftAccess");
        agent.projectId=text(row,row.has("projectId")?"projectId":"bbProjectId"); agent.threadId=text(row,"threadId"); agent.parentId=text(row,"parentId");
        agent.spawn=obj(row,"spawn").deepCopy(); agent.archived=flag(row,"archived");
        agent.removed=flag(row,"bodyRemoved"); agent.lost=flag(row,"bodyLost");
        agents.put(agent.id,agent);
    }
    private synchronized CompletableFuture<Void> save() {
        if(records==null) return CompletableFuture.completedFuture(null);
        var rows=new JsonArray();
        for(var agent:agents.values()) rows.add(object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,
            "minecraftAccess",agent.minecraftAccess,"projectId",agent.projectId,"threadId",agent.threadId,"parentId",agent.parentId,
            "spawn",agent.spawn,"archived",agent.archived,"bodyRemoved",agent.removed,"bodyLost",agent.lost));
        var data=object("version",2,"worldId",loadedWorldId,"agents",rows); var file=records;
        return CompletableFuture.runAsync(() -> { try { JsonState.write(file,data); } catch(Exception failure) { throw new CompletionException(failure); } },disk);
    }
    private CompletableFuture<JsonElement> rpc(String op,JsonObject arguments) {
        var request=arguments.deepCopy(); request.addProperty("op",op); return bb.call(request);
    }
    /** A BB change to an agent's thread; Minecraft rereads the agent once it settles. */
    private synchronized CompletableFuture<JsonElement> threadRpc(String op,String id,JsonObject arguments) { return guarded(() -> {
        var agent=require(id); String session=loadedSession;
        return threadRead(op,id,arguments).whenComplete((done,failure) -> { synchronized(this) { if(currentAgent(agent,session)) refresh(id); } });
    }); }
    private synchronized CompletableFuture<JsonElement> threadRead(String op,String id,JsonObject arguments) { return guarded(() -> {
        var agent=require(id);
        if(agent.threadId.isBlank()) return failed("This agent has not started a conversation yet.");
        var request=arguments.deepCopy(); request.addProperty("threadId",agent.threadId);
        return rpc(op,request);
    }); }
    private synchronized Agent require(String id) {
        if(!currentSession(loadedSession)) throw new IllegalStateException("world_session_changed");
        var agent=agents.get(id); if(agent==null) throw new IllegalArgumentException("Unknown agent: "+id); return agent;
    }
    private boolean currentSession(String session) { return session!=null && session.equals(loadedSession) && session.equals(worldSession.get()); }
    private boolean currentAgent(Agent agent,String session) { return currentSession(session) && agents.get(agent.id)==agent; }
    private void requireCurrent(Agent agent,String session) { if(!currentAgent(agent,session)) throw new IllegalStateException("world_session_changed"); }
    synchronized String findByBody(UUID body) { return currentSession(loadedSession) ? agents.values().stream().filter(a -> a.body!=null && a.body.entityUuid().equals(body.toString()) && game.belongsToCurrentWorld(a.body)).map(a -> a.id).findFirst().orElse(null) : null; }
    synchronized Set<String> retiredBodies() {
        if(!currentSession(loadedSession)) return Set.of();
        return agents.values().stream().filter(a -> a.removed).map(a -> a.id).collect(Collectors.toSet());
    }
    synchronized Map<String,GameAccess.AgentState> agentStates() {
        var result=new HashMap<String,GameAccess.AgentState>();
        if(!currentSession(loadedSession)) return result;
        for(var agent:agents.values()) if(!agent.removed) {
            var state=snapshot(agent.id); String activity=!text(state,"attention").isBlank()?"needs_input":flag(state,"turnActive")?"working":flag(state,"unread")?"done":"idle";
            result.put(agent.id,new GameAccess.AgentState(agent.projectId,activity));
        }
        return result;
    }
    @Override public synchronized JsonArray list() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonArray worldAgents() { var rows=new JsonArray(); if(currentSession(loadedSession)) for(var agent:agents.values()) if(!agent.removed && game.belongsToCurrentWorld(agent.body)) rows.add(snapshot(agent.id)); return rows; }
    @Override public synchronized JsonObject snapshot(String id) {
        var agent=agents.get(id); if(agent==null || !currentSession(loadedSession)) return object("id",id,"status","unavailable");
        var thread=obj(agent.remote,"thread");
        String agentError=text(agent.remote,"error");
        boolean started=!agent.threadId.isBlank();
        var state=object("id",agent.id,"name",agent.name,"body",agent.body,"settings",agent.settings,"projectId",agent.projectId,
            "threadId",agent.threadId,"parentAgentId",agent.parentId,"thread",thread,
            "executionOptions",started?obj(agent.remote,"executionOptions"):agent.spawn,
            "interactions",array(agent.remote,"interactions"),"queuedMessages",array(agent.remote,"queuedMessages"),
            "status",connected && agentError.isBlank()?text(thread,"status").isBlank()?"idle":text(thread,"status"):"disconnected",
            "lifecycle",agent.removed?"removed":"active","bodyRemoved",agent.removed,"bodyLost",agent.lost,"minecraftAccess",agent.minecraftAccess,
            "currentWorld",game.belongsToCurrentWorld(agent.body),"color",text(agent.settings,"color"),"communication",text(agent.settings,"communication"),
            "taskTitle",text(thread,"title"),"providerId",started?text(thread,"providerId"):text(agent.spawn,"providerId"),"bodyType",text(agent.settings,"body"),"gamePaused",game.isPaused());
        boolean active=connected && ACTIVE.contains(text(thread,"status"));
        state.addProperty("turnActive",active); state.addProperty("canSteer",active);
        state.addProperty("conversationArchived",started ? thread.has("archivedAt") && !thread.get("archivedAt").isJsonNull() : agent.archived);
        state.addProperty("unread",thread.has("latestAttentionAt") && (!thread.has("lastReadAt") || thread.get("lastReadAt").isJsonNull() || thread.get("latestAttentionAt").getAsLong()>thread.get("lastReadAt").getAsLong()));
        state.addProperty("replyVersion",thread.has("latestAttentionAt")?thread.get("latestAttentionAt").getAsLong():0);
        state.addProperty("attention",array(agent.remote,"interactions").isEmpty()?"":"input");
        var live=agent.body==null?new JsonObject():game.cached(agent.body);
        state.addProperty("bodyLoaded",!live.isEmpty()); state.addProperty("following",agent.body!=null && game.following(agent.body));
        state.addProperty("followingSuspended",flag(live,"followingSuspended")); state.addProperty("followPauseReason",text(live,"followPauseReason"));
        if(live.has("action")) state.add("action",live.get("action"));
        int pending=scopes.entrySet().stream().filter(e -> agent.id.equals(scopeAgents.get(e.getKey()))).mapToInt(e -> e.getValue().pendingCount()).sum();
        state.addProperty("pendingWorldTools",pending); state.addProperty("waitingForGame",game.isPaused() && pending>0);
        if(!agentError.isBlank()) state.addProperty("error",agentError);
        else if(!error.isBlank()) state.addProperty("error",error);
        return state;
    }
    @Override public synchronized void markRead(String id,long version) { if(currentSession(loadedSession) && connected && !require(id).threadId.isBlank()) threadRpc("agent.markRead",id,new JsonObject()).exceptionally(f -> null); }
    @Override public CompletableFuture<JsonObject> backendStatus() { return rpc("backend.status",new JsonObject()).thenApply(value -> { var status=value.getAsJsonObject(); status.addProperty("connected",connected); status.addProperty("error",error); return status; }).exceptionally(failure -> object("connected",false,"error",message(failure))); }
    @Override public CompletableFuture<JsonObject> backendConfig() { return rpc("system.config",new JsonObject()).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> setDefaultProvider(String providerId) { return rpc("system.defaultProvider.set",object("providerId",providerId)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> projectExecutionOptions(String projectId) {
        if(projectId.isBlank() || projectId.equals("minecraft")) return CompletableFuture.completedFuture(new JsonObject());
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
    @Override public CompletableFuture<JsonArray> environmentProviders(String projectId) { return environmentProviders(projectId,""); }
    @Override public CompletableFuture<JsonArray> environmentProviders(String projectId,String hostId) {
        var args=object("projectId",projectId);
        if(hostId!=null && !hostId.isBlank()) args.addProperty("hostId",hostId);
        return rpc("environment.providers",args).thenApply(JsonElement::getAsJsonArray);
    }
    /** BB's projects, led by this world's own project ("minecraft" until it is first used). */
    @Override public synchronized JsonObject projects() {
        var world=game.worldInfo(); String id=text(world,"worldProjectId");
        var rows=new JsonArray(); JsonObject own=object("id","minecraft","sources",new JsonArray());
        for(var row:projectRows) {
            var project=row.getAsJsonObject().deepCopy();
            if(!id.isBlank() && text(project,"id").equals(id)) own=project; else rows.add(project);
        }
        own.addProperty("name","This world"); own.addProperty("kind","world");
        var result=new JsonArray(); result.add(own); result.addAll(rows);
        return object("projects",result,"world",world);
    }
    private static final JsonObject WORKSPACE=object("type","provider","environmentProviderId","project-checkout");
    private boolean isWorldProject(String projectId) {
        return projectId.equals("minecraft") || !projectId.isBlank() && projectId.equals(text(game.worldInfo(),"worldProjectId"));
    }
    private static Path workspace(JsonObject world) { return Path.of(text(world,"directory")).resolve("too-many-agents/workspace"); }
    /** This world's project, whose folder is the save's workspace; agents in it share that folder. */
    private synchronized CompletableFuture<String> worldProject(String session) {
        if(!currentSession(session)) return failed("world_session_changed");
        var world=game.worldInfo(); String existing=text(world,"worldProjectId");
        if(!existing.isBlank()) return CompletableFuture.completedFuture(existing);
        if(worldProject!=null) return worldProject;
        var created=CompletableFuture.supplyAsync(() -> {
            try { return Files.createDirectories(workspace(world)).toString(); } catch(Exception failure) { throw new CompletionException(failure); }
        },disk).thenCompose(path -> rpc("project.create",object("name","Minecraft: "+text(world,"name"),"source",object("type","local_path","hostId","local","path",path))))
            .thenCompose(project -> { String id=text(project.getAsJsonObject(),"id"); return game.worldCommand(object("operation","world-project","projectId",id),session).thenApply(done -> id); })
            .thenApply(id -> {
                synchronized(this) { if(!currentSession(session)) throw new IllegalStateException("world_session_changed"); for(var agent:agents.values()) if(agent.projectId.equals("minecraft")) agent.projectId=id; save(); }
                polling.execute(this::sync);
                return id;
            });
        worldProject=created;
        created.whenComplete((id,failure) -> { if(failure!=null) synchronized(this) { if(worldProject==created) worldProject=null; } });
        return created;
    }
    /** A moved save keeps its project; point the project at the save's new workspace. */
    private void relocateWorldProject(JsonArray projects,JsonObject world) {
        String id=text(world,"worldProjectId"), path=workspace(world).toString();
        for(var row:projects) {
            var project=row.getAsJsonObject();
            if(!text(project,"id").equals(id)) continue;
            for(var value:array(project,"sources")) {
                var source=value.getAsJsonObject();
                if(flag(source,"isDefault") && !text(source,"path").equals(path)) {
                    bb.call(object("op","project.source.update","projectId",id,"sourceId",text(source,"id"),"path",path)).join();
                    source.addProperty("path",path);
                }
            }
        }
    }
    @Override public CompletableFuture<JsonObject> projectCommand(JsonObject request) { return guarded(() -> {
        String operation=text(request,"operation");
        if(operation.equals("world-resolve") || operation.startsWith("bounds-") || operation.startsWith("station-")) {
            var copy=request.deepCopy(); if(!text(copy,"agentId").isBlank()) copy.addProperty("agentProjectId",require(text(copy,"agentId")).projectId);
            return game.worldCommand(copy,worldSession.get());
        }
        return rpc("project."+operation,request).thenApply(JsonElement::getAsJsonObject).whenComplete((done,failure) -> polling.execute(this::sync));
    }); }
    @Override public synchronized JsonArray profiles() { var rows=new JsonArray(); profiles.forEach((name,settings) -> rows.add(object("name",name,"settings",settings))); return rows; }
    @Override public synchronized CompletableFuture<Void> saveProfile(String name,JsonObject settings) {
        if(name==null || name.isBlank() || name.length()>80) return failed("Profile name must contain 1–80 characters.");
        profiles.put(name,BodySettings.profile(settings)); var saved=JSON.toJsonTree(profiles).getAsJsonObject();
        return CompletableFuture.runAsync(() -> { try { JsonState.write(directory.resolve("body-profiles-v1.json"),saved); } catch(Exception e) { throw new CompletionException(e); } },disk);
    }

    @Override public CompletableFuture<String> spawn(JsonObject request) {
        var settings=BodySettings.copy(request); var spawn=new JsonObject();
        for(String key:SPAWN_FIELDS) if(request.has(key) && !request.get(key).isJsonNull()) spawn.add(key,request.get(key));
        String session=worldSession.get(), id=UUID.randomUUID().toString();
        synchronized(this) {
            if(!Objects.equals(loadedSession,session) || !connected) return failed("BB is disconnected or the world is still loading.");
            if(text(settings,"name").isBlank()) settings.addProperty("name",StarterAgents.name(agents.values().stream().map(a -> a.name).collect(Collectors.toSet())));
        }
        boolean access=!request.has("minecraftAccess") || flag(request,"minecraftAccess");
        // New agents belong to this world unless another project is chosen.
        String chosen=text(spawn,"projectId");
        var project=chosen.isBlank() || isWorldProject(chosen) ? worldProject(session).thenApply(world -> {
            spawn.addProperty("projectId",world); spawn.add("environment",WORKSPACE.deepCopy()); return world;
        }) : CompletableFuture.completedFuture(chosen).thenApply(projectId -> {
            // BB would otherwise default a project to a worktree; that is an explicit choice here, the project folder is not.
            boolean folder;
            synchronized(this) { folder=false; for(var row:projectRows) if(text(row.getAsJsonObject(),"id").equals(projectId)) folder=!array(row.getAsJsonObject(),"sources").isEmpty(); }
            String environment=text(obj(spawn,"environment"),"type");
            if(folder && (environment.isBlank() || environment.equals("project-default"))) spawn.add("environment",WORKSPACE.deepCopy());
            return projectId;
        });
        return project.thenCompose(projectId -> createBody(id,"",projectId,access,settings,spawn,session,null,System.currentTimeMillis()+10_000)).thenCompose(agent -> {
            String task=text(request,"initialTask");
            if(task.isBlank()) return CompletableFuture.completedFuture(id);
            var input=new JsonArray(); input.add(object("type","text","text",task,"mentions",new JsonArray()));
            return start(agent,object("input",input)).thenApply(done -> id);
        });
    }
    /** Creates and saves a body. A body is never created twice for one request. */
    private CompletableFuture<Agent> createBody(String id,String parentId,String projectId,boolean access,JsonObject requested,JsonObject spawn,String session,GameAccess.ToolScope scope,long expiresAt) {
        var settings=BodySettings.copy(requested); String name=text(settings,"name");
        if(name.isBlank()) return failed("invalid_agent_name");
        synchronized(this) {
            if(text(settings,"body").isBlank()) settings.addProperty("body",StarterAgents.body());
            if(text(settings,"color").isBlank()) settings.addProperty("color",AgentColor.random(agents.values().stream().map(a -> text(a.settings,"color")).collect(Collectors.toSet())));
        }
        if(!settings.has("mode")) settings.addProperty("mode","survival");
        if(!settings.has("communication")) settings.addProperty("communication","project");
        if(settings.has("behaviors")) validateBehaviors(settings.get("behaviors"));
        var agent=new Agent(); agent.id=id; agent.name=name; agent.parentId=parentId; agent.projectId=projectId; agent.minecraftAccess=access; agent.settings=settings; agent.spawn=spawn;
        String stationId=text(settings,"stationId");
        var assigned=stationId.isBlank()?CompletableFuture.completedFuture(new JsonObject()):game.worldCommand(object("operation","station-assign","stationId",stationId,"agentId",id,"agentProjectId",projectId),session,scope,expiresAt);
        return assigned.thenCompose(done -> parentId.isBlank()
            ? game.spawn(name,text(settings,"body"),id,projectId,session,expiresAt)
            : game.spawnNear(name,text(settings,"body"),id,projectId,require(parentId).body,session,scope,expiresAt)
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
    /** Starts the agent's BB thread with its first message. Never repeated: BB reports the thread even if this reply is lost. */
    private CompletableFuture<Void> start(Agent agent,JsonObject send) {
        String session=worldSession.get();
        if(isWorldProject(text(agent.spawn,"projectId")) || agent.spawn.isEmpty() && isWorldProject(agent.projectId))
            return worldProject(session).thenCompose(world -> {
                synchronized(this) { requireCurrent(agent,session); agent.projectId=world; agent.spawn.addProperty("projectId",world); agent.spawn.add("environment",WORKSPACE.deepCopy()); }
                return startThread(agent,send);
            });
        return startThread(agent,send);
    }
    private CompletableFuture<Void> startThread(Agent agent,JsonObject send) {
        JsonObject request;
        String session=worldSession.get();
        synchronized(this) {
            requireCurrent(agent,session);
            if(agent.archived) return failed("Unarchive this agent before sending its first prompt.");
            if(agent.starting) return failed("This agent's conversation is already starting.");
            var spawn=agent.spawn.deepCopy();
            for(var entry:send.entrySet()) if(!entry.getKey().equals("mode")) spawn.add(entry.getKey(),entry.getValue());
            var parent=agents.get(agent.parentId);
            request=object("worldId",loadedWorldId,"agentId",agent.id,"minecraftAccess",agent.minecraftAccess,"spawn",spawn,"parentThreadId",parent==null?"":parent.threadId);
            agent.starting=true;
        }
        return rpc("agent.start",request).thenAccept(thread -> { synchronized(this) { requireCurrent(agent,session); bind(agent.id,text(thread.getAsJsonObject(),"id"),session); } })
            .whenComplete((done,failure) -> { synchronized(this) { agent.starting=false; } });
    }
    private synchronized void bind(String id,String threadId,String session) {
        if(!currentSession(session)) return;
        var agent=agents.get(id);
        if(agent==null || !agent.threadId.isBlank() || threadId.isBlank()) return;
        agent.threadId=threadId; agent.spawn=new JsonObject(); agent.archived=false;
        save(); refresh(id);
    }

    @Override public synchronized CompletableFuture<Void> updateSettings(String id,JsonObject settings) { return guarded(() -> {
        var agent=require(id); var physical=new JsonObject(); var patch=new JsonObject();
        String session=loadedSession;
        for(var entry:settings.entrySet()) {
            if(Set.of("title","model","reasoningLevel","serviceTier","permissionMode").contains(entry.getKey())) patch.add(entry.getKey(),entry.getValue());
            else if(Set.of("name","body","mode","cheats","following","followReturn","color","communication","behaviors").contains(entry.getKey())) physical.add(entry.getKey(),entry.getValue());
        }
        boolean started=!agent.threadId.isBlank();
        if(started && (patch.has("permissionMode") || patch.has("serviceTier"))) return failed("Choose permissions and speed in the message composer; BB saves them when you send.");
        if(physical.has("behaviors")) validateBehaviors(physical.get("behaviors"));
        var update=physical.isEmpty()?CompletableFuture.completedFuture(agent.body):game.updateSettings(agent.body,session,physical);
        return update.thenCompose(body -> {
            synchronized(this) {
                requireCurrent(agent,session);
                agent.body=body;
                if(!physical.isEmpty()) { for(var e:game.settings(body).entrySet()) agent.settings.add(e.getKey(),e.getValue()); agent.name=text(agent.settings,"name"); }
                if(!started) for(var e:patch.entrySet()) agent.spawn.add(e.getKey(),e.getValue());
            }
            return save();
        }).thenCompose(done -> { synchronized(this) { requireCurrent(agent,session); return !started || patch.isEmpty()?CompletableFuture.completedFuture(null):threadRpc("agent.update",id,object("patch",patch)).thenApply(v -> null); } });
    }); }
    @Override public synchronized CompletableFuture<Void> openInventory(String id) { return guarded(() -> game.openInventory(require(id).body,loadedSession,id)); }
    @Override public synchronized CompletableFuture<JsonObject> inventory(String id) { return guarded(() -> game.inventory(require(id).body,loadedSession)); }
    @Override public synchronized CompletableFuture<Void> setFollowing(String id,boolean enabled) { return guarded(() -> { var a=require(id); String session=loadedSession; return game.setFollowing(a.body,session,enabled).thenCompose(done -> { synchronized(this) { requireCurrent(a,session); for(var e:game.settings(a.body).entrySet()) a.settings.add(e.getKey(),e.getValue()); } return save(); }); }); }
    synchronized void bodyLost(GameAccess.Body body) { if(!currentSession(loadedSession)) return; for(var a:agents.values()) if(Objects.equals(a.body,body)) { a.lost=true; closeScopes(a.id,"body_lost"); } save(); }
    @Override public synchronized CompletableFuture<Void> checkBody(String id) { return guarded(() -> {
        var a=require(id); if(!a.lost || a.removed) return CompletableFuture.completedFuture(null);
        String session=loadedSession;
        return game.recoverBody(a.body,a.id,a.projectId,a.settings,session).thenCompose(done -> { synchronized(this) { requireCurrent(a,session); a.lost=false; } return save(); });
    }); }
    CompletableFuture<Void> sendMention(String id,String message,JsonObject pointing) { return send(id,object("text",message,"pointing",pointing)); }
    @Override public synchronized CompletableFuture<Void> send(String id,JsonObject message) { return guarded(() -> {
        var pointing=obj(message,"pointing");
        var input=new JsonArray();
        input.add(object("type","text","text",text(message,"text")+(pointing.isEmpty()?"":"\nMinecraft pointing context: "+pointing),"mentions",new JsonArray()));
        for(var image:array(message,"images")) input.add(object("type","localImage","path",image.getAsString()));
        String delivery=text(message,"delivery");
        var send=object("input",input,"mode",Set.of("auto","queue-if-active","steer-if-active","start","steer").contains(delivery)?delivery:"queue-if-active");
        for(String key:List.of("model","reasoningLevel","serviceTier","permissionMode")) if(!text(message,key).isBlank()) send.addProperty(key,text(message,key));
        var agent=require(id);
        if(agent.threadId.isBlank()) return start(agent,send);
        return threadRpc("agent.send",id,object("send",send)).thenApply(done -> null);
    }); }
    @Override public CompletableFuture<Void> steerQueued(String id,String messageId) { return threadRpc("queue.send",id,object("args",object("queuedMessageId",messageId,"mode","steer"))).thenApply(done -> null); }
    @Override public CompletableFuture<Void> cancelQueued(String id,String messageId) { return threadRpc("queue.delete",id,object("args",object("queuedMessageId",messageId))).thenApply(done -> null); }
    @Override public CompletableFuture<JsonObject> transcript(String id,JsonObject query) { return threadRead("timeline",id,object("query",query)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<JsonObject> chatAsset(String id,String kind,String source) {
        return threadRead("chat.asset",id,object("kind",kind,"source",source)).thenApply(JsonElement::getAsJsonObject);
    }
    @Override public CompletableFuture<Void> openChatLink(String id,String target) {
        return threadRead("chat.open",id,object("target",target)).thenApply(ignored->null);
    }
    CompletableFuture<JsonObject> developmentChatAsset(String threadId,String kind,String source) {
        if(!DevelopmentWorld.ENABLED)return failed("Development only");
        return rpc("chat.asset",object("threadId",threadId,"kind",kind,"source",source)).thenApply(JsonElement::getAsJsonObject);
    }
    CompletableFuture<Void> developmentChatLink(String threadId,String target) {
        if(!DevelopmentWorld.ENABLED)return failed("Development only");
        return rpc("chat.open",object("threadId",threadId,"target",target)).thenApply(ignored->null);
    }
    @Override public CompletableFuture<JsonObject> timelineTurnSummaryDetails(String id,JsonObject query) { return threadRead("timeline.summary",id,object("query",query)).thenApply(JsonElement::getAsJsonObject); }
    @Override public CompletableFuture<Void> respond(String id,String requestId,JsonObject resolution) { return threadRpc("interaction.resolve",id,object("interactionId",requestId,"resolution",resolution)).thenApply(done -> null); }
    @Override public synchronized CompletableFuture<Void> interrupt(String id) { return guarded(() -> {
        var agent=require(id);
        closeScopes(id,"interrupted");
        return agent.threadId.isBlank()?CompletableFuture.completedFuture(null):threadRpc("agent.stop",id,new JsonObject()).thenApply(done -> null);
    }); }
    @Override public synchronized CompletableFuture<Void> archiveConversation(String id,boolean archive) { return guarded(() -> {
        var agent=require(id);
        if(!agent.threadId.isBlank()) return threadRpc(archive?"agent.archive":"agent.unarchive",id,new JsonObject()).thenApply(done -> null);
        agent.archived=archive;
        return save();
    }); }
    @Override public synchronized CompletableFuture<Void> remove(String id,boolean archive) { return guarded(() -> {
        var agent=require(id); String session=loadedSession;
        closeScopes(id,"body_removed");
        return removeBody(agent,session,null,0).thenCompose(done -> { synchronized(this) { requireCurrent(agent,session); return archive?archiveConversation(id,true):CompletableFuture.completedFuture(null); } });
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
            if(request.get("protocol").getAsInt()!=2 || session==null || !session.equals(loadedSession) || !session.equals(text(request,"worldSessionId"))
                || !loadedWorldId.equals(text(request,"worldId"))) return failed("world_session_changed");
            long expiresAt=request.get("expiresAt").getAsLong();
            switch(op) {
                case "agents": {
                    var rows=new JsonArray();
                    for(var a:agents.values()) if(!a.removed) rows.add(object("agentId",a.id,"name",a.name,"threadId",a.threadId,"parentAgentId",a.parentId,
                        "projectId",a.projectId,"minecraftAccess",a.minecraftAccess,"settings",a.settings));
                    return CompletableFuture.completedFuture(object("agents",rows));
                }
                case "changed": {
                    String id=text(request,"agentId"), threadId=text(request,"threadId");
                    if(flag(request,"bind")) bind(id,threadId,session);
                    else if(agents.containsKey(id) && agents.get(id).threadId.equals(threadId)) refresh(id);
                    return CompletableFuture.completedFuture(new JsonObject());
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
            // The acting agent: the caller itself, or the parent creating or removing a child.
            String actor=op.equals("spawn_body") || op.equals("remove_body")?text(request,"parentAgentId"):text(request,"agentId");
            var agent=require(actor);
            if(agent.removed || !game.belongsToCurrentWorld(agent.body)) return failed("agent_body_unavailable");
            if(op.equals("notify")) {
                var noticeScope=game.newToolScope(session);
                return game.callInTurn(noticeScope,agent.body,GameAccess.Operation.NOTIFY,object("message",text(request,"message")),expiresAt)
                    .whenComplete((done,failure) -> noticeScope.close("notification_finished"));
            }
            if(agent.threadId.isBlank() || !agent.threadId.equals(text(request,"threadId"))) return failed("This thread is not the Minecraft agent's conversation.");
            UUID.fromString(requestId);
            if(cancelled.remove(requestId) || scopes.containsKey(requestId)) return failed("tool_cancelled");
            var scope=game.newToolScope(session);
            scopes.put(requestId,scope); scopeAgents.put(requestId,actor);
            CompletableFuture<JsonObject> work=switch(op) {
                case "tool" -> game.callInTurn(scope,agent.body,AgentSurface.operation(agent.minecraftAccess,text(request,"tool")),obj(request,"arguments"),expiresAt).thenApply(AgentService::toolResult);
                case "spawn_body" -> {
                    String id=UUID.randomUUID().toString();
                    var settings=obj(request,"settings").deepCopy(); settings.addProperty("name",text(request,"name"));
                    if(!text(request,"stationId").isBlank()) settings.addProperty("stationId",text(request,"stationId"));
                    yield createBody(id,actor,text(request,"projectId"),flag(request,"minecraftAccess") && agent.minecraftAccess,settings,new JsonObject(),session,scope,expiresAt)
                        .thenApply(created -> object("agentId",id,"body",created.body));
                }
                case "remove_body" -> {
                    var child=require(text(request,"agentId"));
                    if(!child.parentId.equals(actor)) yield failed("Only an agent's own child may be removed.");
                    closeScopes(child.id,"body_removed");
                    yield removeBody(child,session,scope,expiresAt).thenApply(done -> object("agentId",child.id));
                }
                default -> failed("unknown_callback_operation");
            };
            return work.whenComplete((done,failure) -> { synchronized(this) { if(scopes.remove(requestId)!=null) scope.close("tool_finished"); scopeAgents.remove(requestId); } });
        } catch(Exception failure) { return CompletableFuture.failedFuture(failure); }
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
}
