package toomanyagents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** All live world access runs on the integrated server thread. */
final class GameAccess {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long QUEUE_SECONDS = 5;
    enum Operation { OBSERVE, POV, BLOCKS, ACTION, ACTION_STATUS, CANCEL, COMMAND, SCRIPT }
    // What an agent's body perceives by default.
    private static final double OBSERVE_RADIUS = 16, LOOK_DISTANCE = 16;
    private static final int ENTITY_LIMIT = 64;
    private static final PovCapture.Settings POV = new PovCapture.Settings(960, 540, 70);
    record Body(String entityUuid, String world, String dimension) {}
    /** An agent's project, activity and Minecraft access, published by AgentService. */
    record AgentState(String projectId, String activity, boolean minecraftAccess) {}
    private record PublishedWorld(String path, String session) {}

    private final Supplier<MinecraftServer> server;
    private final Supplier<String> worldSession;
    private final Supplier<UUID> localPlayer;
    private final BooleanSupplier paused;
    private volatile PublishedWorld publishedWorld;
    private volatile JsonObject worldInfo = new JsonObject();
    private final Map<Body, AgentActions> actions = new HashMap<>();
    private Map<String, AgentState> agentStates = Map.of();
    private final ConcurrentHashMap<Body, JsonObject> bodySnapshots = new ConcurrentHashMap<>();
    private String actionSession;
    private ScriptScoreboard scriptScoreboard;
    private Set<UUID> failedBodyCleanup;
    private String cleanupSession;
    private PovCapture pov;
    private final Object queueLock = new Object();
    private final Set<ToolScope> toolScopes = new HashSet<>();
    private final ArrayDeque<PendingCall> toolQueue = new ArrayDeque<>();
    private record ActionStop(Body body, String session, String scriptId) {}
    private final Set<ActionStop> actionStops = ConcurrentHashMap.newKeySet();
    private long activeNanos, clockNanos = System.nanoTime();
    private boolean clockPaused;

    /** One registered agent turn. Closing it revokes every call that has not started. */
    final class ToolScope {
        private final String session;
        private final MinecraftServer current;
        private String closed;
        private int pending;
        private final Set<CompletableFuture<JsonObject>> waits = new HashSet<>();

        private ToolScope(String session, MinecraftServer current) {
            this.session = session;
            this.current = current;
        }

        int pendingCount() { synchronized (queueLock) { return pending; } }

        void close(String reason) {
            var rejected = new ArrayList<PendingCall>();
            var waiting = new ArrayList<CompletableFuture<JsonObject>>();
            synchronized (queueLock) {
                if (closed != null) return;
                closed = reason == null ? "turn_closed" : reason;
                toolScopes.remove(this);
                waiting.addAll(waits);
                waits.clear();
                toolQueue.removeIf(call -> {
                    if (call.scope != this) return false;
                    pending--;
                    rejected.add(call);
                    return true;
                });
            }
            // Completion can call AgentService; never run those callbacks with queueLock held.
            for (var call : rejected) call.result.completeExceptionally(error(closed));
            for (var wait : waiting) wait.completeExceptionally(error(closed));
        }
    }

    private final class PendingCall {
        final ToolScope scope;
        final Body body;
        final Operation operation;
        final JsonObject args;
        final long deadline;
        final long expiresAt;
        final ScriptStream stream;
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();

        PendingCall(ToolScope scope, Body body, Operation operation, JsonObject args, long expiresAt, ScriptStream stream) {
            this.scope = scope;
            this.body = body;
            this.operation = operation;
            this.args = args;
            this.stream = stream;
            deadline = activeNanos + TimeUnit.SECONDS.toNanos(QUEUE_SECONDS);
            this.expiresAt = expiresAt;
        }
    }

    boolean isPaused() { return paused.getAsBoolean(); }

    ToolScope newToolScope(String expectedSession) {
        synchronized (queueLock) {
            var scope = new ToolScope(expectedSession, server.get());
            if (scope.current == null || expectedSession == null || !expectedSession.equals(worldSession.get())) {
                scope.closed = "world_session_changed";
            } else toolScopes.add(scope);
            return scope;
        }
    }

    CompletableFuture<JsonObject> callInTurn(ToolScope scope, Body body, Operation operation, JsonObject args) {
        return callInTurn(scope,body,operation,args,Long.MAX_VALUE);
    }

    CompletableFuture<JsonObject> callInTurn(ToolScope scope, Body body, Operation operation, JsonObject args, long expiresAt) {
        return callInTurn(scope, body, operation, args, expiresAt, null);
    }

    CompletableFuture<JsonObject> callInTurn(ToolScope scope, Body body, Operation operation, JsonObject args, long expiresAt, ScriptStream stream) {
        var arguments = args == null ? new JsonObject() : args.deepCopy();
        synchronized (queueLock) {
            updateQueueClock();
            if (scope.closed != null) return CompletableFuture.failedFuture(error(scope.closed));
            if (scope.current != server.get() || !Objects.equals(scope.session, worldSession.get())) {
                return CompletableFuture.failedFuture(error("world_session_changed"));
            }
            if (scope.pending >= 16 || toolQueue.size() >= 256) {
                return CompletableFuture.failedFuture(error("world_tool_queue_full"));
            }
            var call = new PendingCall(scope, body, operation, arguments, expiresAt, stream);
            toolQueue.addLast(call);
            scope.pending++;
            return call.result;
        }
    }

    /** Only serialized session/pause metadata is read here; the client never touches world objects. */
    void clientTick() {
        var rejected = new ArrayList<PendingCall>();
        var reasons = new ArrayList<String>();
        var waiting = new ArrayList<CompletableFuture<JsonObject>>();
        synchronized (queueLock) {
            updateQueueClock();
            for (var scope : toolScopes) {
                if (scope.current != server.get() || !Objects.equals(scope.session, worldSession.get())) {
                    scope.closed = "world_session_changed";
                    waiting.addAll(scope.waits);
                    scope.waits.clear();
                }
            }
            toolScopes.removeIf(scope -> scope.closed != null);
            toolQueue.removeIf(call -> {
                String reason = call.scope.closed;
                if (reason == null && call.result.isCancelled()) reason = "tool_call_cancelled";
                if (reason == null && (activeNanos >= call.deadline || System.currentTimeMillis() >= call.expiresAt)) reason = "expired_before_execution";
                if (reason == null) return false;
                call.scope.pending--;
                rejected.add(call);
                reasons.add(reason);
                return true;
            });
        }
        for (int i = 0; i < rejected.size(); i++) rejected.get(i).result.completeExceptionally(error(reasons.get(i)));
        for (var wait : waiting) wait.completeExceptionally(error("world_session_changed"));
    }

    private void updateQueueClock() {
        long now = System.nanoTime();
        boolean nowPaused = isPaused();
        // Intervals crossing a pause transition do not consume the unpaused budget.
        if (!clockPaused && !nowPaused) activeNanos += now - clockNanos;
        clockNanos = now;
        clockPaused = nowPaused;
    }

    private void drainTools(MinecraftServer current) {
        clientTick();
        for (int i = 0; i < 256; i++) {
            PendingCall call;
            String rejection = null;
            synchronized (queueLock) {
                updateQueueClock();
                if (isPaused() || server.get() != current) return;
                call = toolQueue.pollFirst();
                if (call == null) return;
                call.scope.pending--;
                // This is the start/close linearization point; started calls are never replayed.
                if (call.scope.closed != null) rejection = call.scope.closed;
                else if (call.scope.current != current || !Objects.equals(call.scope.session, worldSession.get())) rejection = "world_session_changed";
                else if (activeNanos >= call.deadline || System.currentTimeMillis() >= call.expiresAt) rejection = "expired_before_execution";
            }
            if (call.result.isCancelled()) continue;
            if (rejection != null) { call.result.completeExceptionally(error(rejection)); continue; }
            try {
                if (call.operation == Operation.POV) {
                    body(current, call.body);
                    pov.capture(UUID.fromString(call.body.entityUuid()), call.scope.session, POV).whenComplete((result, failure) -> {
                        if (failure != null) call.result.completeExceptionally(failure);
                        else call.result.complete(result);
                    });
                } else if (call.stream != null || scriptWait(call.operation, call.args)) {
                    synchronized (queueLock) {
                        if (call.scope.closed != null) throw error(call.scope.closed);
                        call.scope.waits.add(call.result);
                    }
                    call.result.whenComplete((result, failure) -> {
                        synchronized (queueLock) { call.scope.waits.remove(call.result); }
                    });
                    // Register on the owning thread; the result itself contains only JSON.
                    var waiting = call.stream == null ? awaitScript(current, call.body, call.args)
                        : streamScript(current, call.body, call.args, call.stream);
                    waiting.whenComplete((result, failure) -> {
                        if (failure != null) call.result.completeExceptionally(failure);
                        else call.result.complete(result);
                    });
                } else call.result.complete(executeOperation(current, call.body, call.operation, call.args));
            } catch (Exception | LinkageError failure) { call.result.completeExceptionally(failure); }
        }
    }

    /** Stop is accepted even while paused, and applied before the next action controller tick. */
    void requestActionStop(Body body, String expectedSession) {
        requestActionStop(body, expectedSession, null);
    }

    void requestActionStop(Body body, String expectedSession, String scriptId) {
        if (body != null && expectedSession != null && expectedSession.equals(worldSession.get())) {
            actionStops.add(new ActionStop(body, expectedSession, scriptId));
        }
    }

    private void drainActionStops() {
        for (var stop : actionStops) {
            if (!actionStops.remove(stop) || !stop.session.equals(worldSession.get())) continue;
            var controller = actions.get(stop.body);
            if (controller != null && (stop.scriptId == null || controller.controlsScript(stop.scriptId))) controller.releaseScript();
        }
    }

    void setPov(PovCapture capture) { pov = capture; }
    JsonObject cached(Body body) { var value = bodySnapshots.get(body); return value == null ? new JsonObject() : value.deepCopy(); }

    JsonObject settings(Body body) {
        var state = cached(body);
        state.remove("action");
        return state;
    }

    /** World identity published by the server thread, safe for UI and HTTP readers. */
    JsonObject worldInfo() { return worldInfo.deepCopy(); }
    CompletableFuture<JsonObject> worldCommand(JsonObject request, String expectedSession) {
        return worldCommand(request,expectedSession,null,Long.MAX_VALUE);
    }

    CompletableFuture<JsonObject> worldCommand(JsonObject request, String expectedSession, ToolScope scope, long expiresAt) {
        return schedule(expectedSession, expiresAt, current -> {
            synchronized(queueLock) { if(scope!=null && scope.closed!=null) throw error(scope.closed); }
            var state = WorldState.get(current);
            JsonObject result;
            String operation = JsonState.text(request,"operation"), dimension = player(current).level().dimension().location().toString();
            try { result = operation.equals("world-resolve") ? state.resolve(JsonState.text(request,"choice"))
                : operation.equals("world-project") ? state.project(JsonState.text(request,"bbInstanceId"),JsonState.text(request,"projectId"))
                : operation.startsWith("station-") ? state.stations(request,dimension)
                : state.bounds(request,dimension);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Could not save world data.",failure); }
            if (operation.equals("world-resolve") && JsonState.text(request,"choice").equals("copy")) {
                for (var level : current.getAllLevels()) for (var entity : level.getAllEntities()) {
                    var saved = entity.getPersistentData();
                    if (saved.getString("too_many_agents_world").equals(state.copiedFrom())) saved.putString("too_many_agents_world",state.id());
                }
            }
            worldInfo = state.snapshot();
            publishedWorld = new PublishedWorld(state.activeId(),worldSession.get());
            return result;
        });
    }

    String currentWorldId() {
        var current = publishedWorld;
        if (worldInfo.has("needsDecision") && worldInfo.get("needsDecision").getAsBoolean()) throw new IllegalStateException("This world was moved or copied. Choose how to open it in Projects.");
        return current != null && Objects.equals(current.session(),worldSession.get()) ? current.path() : null;
    }

    private boolean worldMatches(String saved, String current) {
        if (Objects.equals(saved,current)) return true;
        if (current.startsWith("unresolved:")) return false;
        var aliases = worldInfo.getAsJsonArray("paths");
        return aliases != null && aliases.contains(new com.google.gson.JsonPrimitive(saved));
    }

    boolean belongsToCurrentWorld(Body body) {
        var current = publishedWorld;
        return body != null && current != null && current.session() != null && server.get() != null
            && Objects.equals(current.session(), worldSession.get()) && worldMatches(body.world(),current.path());
    }

    /** Inspect existing hands only: opening a view never creates or changes an action controller. */
    CompletableFuture<Void> openInventory(Body ref, String expectedSession, String agentId) {
        return schedule(expectedSession, current -> {
            var mob = body(current, ref);
            var controller = actions.get(ref);
            if (controller == null || controller.mob != mob) throw error("inventory_not_ready");
            var viewer = player(current);
            java.util.function.BooleanSupplier valid = () -> expectedSession.equals(worldSession.get())
                && actions.get(ref) == controller && mob.isAlive() && !mob.isRemoved()
                && !mob.getPersistentData().getBoolean("too_many_agents_removing")
                && viewer.isAlive() && viewer.level() == mob.level() && viewer.distanceToSqr(mob) <= 64;
            if (!valid.getAsBoolean()) throw error("Move within eight blocks of the agent to exchange items.");
            viewer.openMenu(new net.minecraft.world.MenuProvider() {
                    @Override public net.minecraft.network.chat.Component getDisplayName() { return mob.getName(); }
                    @Override public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id,
                            net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player player) {
                        return new AgentInventoryMenu(id, inventory, controller.hands.getInventory(), mob.getId(), agentId, valid, controller.hands::save);
                    }
                    // Native cleanup still runs; omit the intermediate close-screen packet.
                    @Override public boolean shouldTriggerClientSideContainerClosingOnOpen() { return false; }
                }, data -> { data.writeVarInt(mob.getId()); data.writeUtf(agentId); });
            return null;
        });
    }

    CompletableFuture<JsonObject> inventory(Body ref, String expectedSession) {
        return schedule(expectedSession, current -> {
            var mob = body(current, ref);
            var controller = actions.get(ref);
            if (controller == null || controller.mob != mob || !Objects.equals(actionSession, expectedSession))
                throw error("inventory_not_ready");
            var hands = controller.hands;
            var inventory = hands.getInventory();
            var result = new JsonObject();
            result.addProperty("available", true);
            result.addProperty("session", expectedSession);
            result.addProperty("entityUuid", ref.entityUuid());
            result.addProperty("name", mob.getName().getString());
            result.addProperty("tick", current.getTickCount());
            result.addProperty("selected", inventory.selected);
            var slots = new JsonArray();
            for (int index = 0; index < inventory.items.size(); index++) {
                var entry = inventoryItem(current, inventory.getItem(index));
                entry.addProperty("slot", index);
                slots.add(entry);
            }
            result.add("slots", slots);
            var equipment = new JsonArray();
            for (var slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                var entry = inventoryItem(current, hands.getItemBySlot(slot));
                entry.addProperty("slot", slot.getName());
                // Equipment references these same inventory stacks, never additional holdings.
                entry.addProperty("inventorySlot", slot == EquipmentSlot.MAINHAND ? inventory.selected
                    : slot == EquipmentSlot.OFFHAND ? 40 : 36 + slot.getIndex());
                equipment.add(entry);
            }
            result.add("equipment", equipment);
            result.add("carried", inventoryItem(current, hands.containerMenu.getCarried()));
            return result;
        });
    }

    private static JsonObject inventoryItem(MinecraftServer current, ItemStack stack) {
        var entry = new JsonObject();
        entry.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        entry.addProperty("count", stack.getCount());
        entry.add("nativeItemStack", ItemStack.OPTIONAL_CODEC.encodeStart(
            current.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow());
        return entry;
    }

    private AgentActions actions(Body ref, Mob mob) {
        if (!Objects.equals(actionSession, worldSession.get())) {
            actions.values().forEach(a -> a.close("world_session_changed"));
            actions.clear(); bodySnapshots.clear(); actionSession = worldSession.get();
        }
        var existing = actions.get(ref);
        if (existing != null && existing.mob != mob) { existing.close("body_reloaded"); actions.remove(ref); }
        return actions.computeIfAbsent(ref, ignored -> {
            var controller = new AgentActions(mob, actionSession, () -> box(mob), () -> player(mob.getServer()),
                request -> scriptChat(mob, request), request -> tabComplete(mob, request));
            controller.hands.collectSink = collected -> entityEvent("playerCollect", mob, collected, null);
            return controller;
        });
    }

    CompletableFuture<Body> updateSettings(Body initialRef, String expectedSession, JsonObject settings) {
        var values = settings.deepCopy();
        return schedule(expectedSession, current -> {
            Body ref = initialRef;
            var mob = body(current, ref);
            String mode = values.has("mode") ? values.get("mode").getAsString() : mob.getPersistentData().getString("too_many_agents_mode");
            BodySettings.mode(mode);
            String name = values.has("name") ? string(values,"name",80).strip() : mob.getName().getString();
            if (name.isBlank()) throw error("invalid_agent_name");
            AgentActions controller = actions(ref,mob);
            boolean physicalChange = List.of("name", "body", "mode").stream()
                .anyMatch(key -> values.has(key) && !Objects.equals(values.get(key), settings(initialRef).get(key)));
            if (controller.busy() && physicalChange) throw error("interrupt_action_before_changing_settings");
            String typeName = values.has("body") ? string(values,"body",200) : BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
            if (!typeName.equals(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())) {
                var typeId = ResourceLocation.tryParse(typeName);
                if (typeId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) throw error("unknown_entity_type");
                var type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
                if (!type.canSummon() || !type.canSerialize() || !type.isEnabled(mob.level().enabledFeatures()) || !(type.create(mob.level()) instanceof Mob replacement)) throw error("invalid_body_type");
                replacement.moveTo(mob.getX(),mob.getY(),mob.getZ(),mob.getYRot(),mob.getXRot());
                if (!mob.level().noCollision(replacement, replacement.getBoundingBox().deflate(0.01))) {
                    // Existing body overlaps the same location; block collision is the relevant check.
                    if (mob.level().getBlockCollisions(replacement,replacement.getBoundingBox()).iterator().hasNext()) throw error("new_body_does_not_fit");
                }
                controller.close("body_changed");
                replacement.getPersistentData().merge(mob.getPersistentData().copy());
                restoreBody(replacement);
                if (!((ServerLevel)mob.level()).addFreshEntity(replacement)) { actions.remove(ref); throw error("replacement_spawn_rejected"); }
                mob.getPersistentData().putBoolean("too_many_agents_removing", true);
                mob.discard(); actions.remove(ref); bodySnapshots.remove(ref);
                mob = replacement;
                ref = new Body(mob.getStringUUID(),world(current),mob.level().dimension().location().toString());
            }
            mob.setCustomName(Component.literal(name)); mob.setCustomNameVisible(true);
            var saved = mob.getPersistentData();
            saved.putString("too_many_agents_mode",mode);
            if (values.has("behaviors")) saved.putString("too_many_agents_behaviors",values.get("behaviors").toString());
            actions(ref,mob).hands.syncBody();
            cacheBody(ref,mob);
            return ref;
        });
    }

    private void cacheBody(Body ref, Mob mob) {
        var saved = mob.getPersistentData();
        var result = new JsonObject();
        result.addProperty("name",mob.getName().getString());
        result.addProperty("body",BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        result.addProperty("mode",BodySettings.mode(saved.getString("too_many_agents_mode")).id);
        result.add("behaviors",behaviors(mob));
        var controller = actions(ref,mob);
        result.add("action",controller.status(""));
        bodySnapshots.put(ref,result);
    }

    GameAccess(Supplier<MinecraftServer> server, Supplier<String> worldSession,
               Supplier<UUID> localPlayer, BooleanSupplier paused) {
        this.server = server;
        this.worldSession = worldSession;
        this.localPlayer = localPlayer;
        this.paused = paused;
    }

    void stopped(MinecraftServer current) {
        publishedWorld = null;
        worldInfo = new JsonObject();
        List<ToolScope> scopes;
        synchronized (queueLock) { scopes = List.copyOf(toolScopes); }
        scopes.forEach(scope -> scope.close("world_closed"));
        actionStops.clear();
        actions.values().forEach(a -> a.close("world_closed"));
        actions.clear(); bodySnapshots.clear(); actionSession = null;
        scriptScoreboard = null;
    }

    CompletableFuture<JsonObject> development(JsonObject request) {
        if (!DevelopmentWorld.ENABLED) return CompletableFuture.failedFuture(error("development_interface_disabled"));
        String session=worldSession.get(),action=request.get("action").getAsString();
        return schedule(session, current -> {
            var human = player(current);
            if (!current.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().equals(DevelopmentWorld.NAME)) throw error("not_development_world");
            return switch (action) {
                case "native_checks" -> DevelopmentChecks.start(human.serverLevel(),human);
                case "pov" -> {
                    var snapshot=DevelopmentChecks.snapshot();
                    if(!snapshot.has("bodyUuid"))throw error("run_native_checks_first");
                    var fixture=human.serverLevel().getEntity(UUID.fromString(snapshot.get("bodyUuid").getAsString()));
                    if(fixture==null || !fixture.getPersistentData().getBoolean("too_many_agents_development_fixture"))throw error("development_fixture_missing");
                    yield snapshot;
                }
                case "save" -> { current.saveEverything(false,true,true); yield new JsonObject(); }
                default -> throw error("unknown_development_action");
            };
        }).thenCompose(result->action.equals("pov") ? pov.capture(UUID.fromString(result.get("bodyUuid").getAsString()),session,POV)
            : CompletableFuture.completedFuture(result));
    }

    CompletableFuture<JsonArray> bodies() {
        return schedule(worldSession.get(), current -> {
            var level = player(current).serverLevel();
            var result = new JsonArray();
            BuiltInRegistries.ENTITY_TYPE.stream()
                .filter(type -> type.canSummon() && type.canSerialize() && type.isEnabled(level.enabledFeatures()))
                .sorted(Comparator.comparing(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()))
                .forEach(type -> {
                    // Factories are the only reliable Mob test for custom types. Never add these probes to the world.
                    try {
                        if (type.create(level) instanceof Mob) {
                            var entry = new JsonObject();
                            entry.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
                            entry.addProperty("label", type.getDescription().getString());
                            result.add(entry);
                        }
                    } catch (RuntimeException | LinkageError exception) {
                        LOG.warn("Cannot inspect NPC body type {}", BuiltInRegistries.ENTITY_TYPE.getKey(type), exception);
                    }
                });
            return result;
        });
    }

    CompletableFuture<Body> spawn(String name, String entityType, String agentId) {
        return spawn(name, entityType, agentId, "", worldSession.get());
    }

    CompletableFuture<Body> spawn(String name, String entityType, String agentId, String projectId, String expectedSession) {
        return spawn(name,entityType,agentId,projectId,expectedSession,Long.MAX_VALUE);
    }

    CompletableFuture<Body> spawn(String name, String entityType, String agentId, String projectId, String expectedSession, long expiresAt) {
        return schedule(expectedSession, expiresAt, current -> {
            var player = player(current);
            var mob = createBody(player, lookedAt(player), name, entityType, agentId, projectId);
            // Face the player who spawned it.
            float yaw = (float) (Math.toDegrees(Math.atan2(player.getZ() - mob.getZ(), player.getX() - mob.getX())) - 90);
            mob.moveTo(mob.getX(), mob.getY(), mob.getZ(), yaw, 0);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            if (!player.serverLevel().addFreshEntity(mob)) throw error("spawn_rejected");
            return new Body(mob.getStringUUID(), world(current), mob.level().dimension().location().toString());
        });
    }

    CompletableFuture<Body> spawnNear(String name, String entityType, String agentId, String projectId, Body parent,
                                      String expectedSession, ToolScope callerScope) {
        return spawnNear(name,entityType,agentId,projectId,parent,expectedSession,callerScope,Long.MAX_VALUE);
    }

    CompletableFuture<Body> spawnNear(String name, String entityType, String agentId, String projectId, Body parent,
                                      String expectedSession, ToolScope callerScope, long expiresAt) {
        return schedule(expectedSession, expiresAt, current -> {
            synchronized (queueLock) {
                if (callerScope != null) {
                    if (callerScope.closed != null) throw error(callerScope.closed);
                    if (callerScope.current != current || !Objects.equals(callerScope.session, expectedSession))
                        throw error("world_session_changed");
                }
            }
            var anchor = body(current, parent);
            var mob = createBody(anchor, null, name, entityType, agentId, projectId);
            // Closing the caller's turn revokes a spawn until this mutation begins.
            synchronized (queueLock) {
                if (callerScope != null && callerScope.closed != null) throw error(callerScope.closed);
            }
            if (!((ServerLevel) anchor.level()).addFreshEntity(mob)) throw error("spawn_rejected");
            return new Body(mob.getStringUUID(), world(current), mob.level().dimension().location().toString());
        });
    }

    /** The space on top of the block under the player's crosshair, or null when not looking at a block. */
    private static BlockPos lookedAt(ServerPlayer player) {
        return player.pick(64, 1, false) instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().above() : null;
    }

    private static Mob createBody(Entity anchor, BlockPos target, String name, String entityType, String agentId, String projectId) {
        if (name == null || name.isBlank() || name.length() > 80) throw error("invalid_agent_name");
        if (agentId == null || agentId.isBlank()) throw error("invalid_agent_id");
        var id = ResourceLocation.tryParse(entityType);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) throw error("unknown_entity_type: " + entityType);
        var type = BuiltInRegistries.ENTITY_TYPE.get(id);
        var level = (ServerLevel) anchor.level();
        if (!type.canSummon() || !type.canSerialize() || !type.isEnabled(level.enabledFeatures())) throw error("entity_type_not_spawnable: " + entityType);
        if (!(type.create(level) instanceof Mob mob)) throw error("entity_type_is_not_a_mob: " + entityType);
        mob.setCustomName(Component.literal(name.strip()));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        mob.setNoAi(true);
        mob.getPersistentData().putString("too_many_agents_mode", "survival");
        mob.getPersistentData().putString("too_many_agents_agent", agentId);
        mob.getPersistentData().putString("too_many_agents_world",WorldState.get(anchor.level().getServer()).id());
        // A body starts inside its box: its station if assigned, otherwise its project's box.
        placeNear(level, anchor, target, mob, WorldState.get(level.getServer()).box(agentId, projectId, level.dimension().location().toString()));
        return mob;
    }

    /** Native item effects have completed; the next frame carries the resulting hand stack. */
    void nativeUseFinished(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) {
        var entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || level.getServer() != server.get()) return;
        for (var controller : actions.values()) {
            if (controller.mob == entity) { controller.hands.nativeUseFinished(event); return; }
        }
    }

    /** Only destructive removal proves loss; chunk unloading and dimension changes do not. */
    Body destroyedBody(Entity entity) {
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)
            || mob.getRemovalReason() == null || !mob.getRemovalReason().shouldDestroy()
            || mob.getPersistentData().getString("too_many_agents_agent").isBlank()
            || mob.getPersistentData().getBoolean("too_many_agents_removing")) return null;
        return new Body(mob.getStringUUID(), world(level.getServer()), level.dimension().location().toString());
    }

    /** Close hands before vanilla copies the entity and its saved inventory to another dimension. */
    void changingDimension(Entity entity, ResourceKey<net.minecraft.world.level.Level> destination) {
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)
            || level.getServer() != server.get() || destination.equals(level.dimension())
            || mob.getPersistentData().getString("too_many_agents_agent").isBlank()
            || !mob.getPersistentData().getString("too_many_agents_world").equals(world(level.getServer()))) return;
        forgetDimension(new Body(mob.getStringUUID(), world(level.getServer()), level.dimension().location().toString()));
    }

    /** Server thread only. Revoke old-reference work without running completion callbacks under queueLock. */
    void forgetDimension(Body previous) {
        var controller = actions.remove(previous);
        if (controller != null) controller.close("body_dimension_changed");
        bodySnapshots.remove(previous);
        actionStops.removeIf(stop -> stop.body.equals(previous));
        var rejected = new ArrayList<PendingCall>();
        synchronized (queueLock) {
            toolQueue.removeIf(call -> {
                if (!previous.equals(call.body)) return false;
                call.scope.pending--;
                rejected.add(call);
                return true;
            });
        }
        for (var call : rejected) call.result.completeExceptionally(error("body_dimension_changed"));
    }

    CompletableFuture<Void> recoverBody(Body ref, String agentId, String projectId, JsonObject settings, String expectedSession) {
        var saved = settings.deepCopy();
        return schedule(expectedSession, current -> {
            if (!Objects.equals(ref.world(), world(current))) throw error("body_world_mismatch");
            // Reusing the UUID also makes recovery safe if its last save was interrupted.
            for (var level : current.getAllLevels()) {
                var existing = level.getEntity(UUID.fromString(ref.entityUuid()));
                if (existing != null) {
                    if (!(existing instanceof Mob mob) || !mob.isAlive() || !agentId.equals(mob.getPersistentData().getString("too_many_agents_agent")))
                        throw error("body_recovery_conflict");
                    cacheBody(ref, mob);
                    return null;
                }
            }
            var player = player(current);
            if (!player.level().dimension().location().toString().equals(ref.dimension()))
                throw error("Return to the agent's original dimension to recover its body.");
            var mob = createBody(player, null, string(saved,"name",80), string(saved,"body",200), agentId, projectId);
            mob.setUUID(UUID.fromString(ref.entityUuid()));
            var data = mob.getPersistentData();
            data.putString("too_many_agents_mode", BodySettings.mode(saved.get("mode").getAsString()).id);
            if (saved.has("behaviors")) data.putString("too_many_agents_behaviors", saved.get("behaviors").toString());
            if (!player.serverLevel().addFreshEntity(mob)) throw error("body_recovery_spawn_rejected");
            cacheBody(ref, mob);
            return null;
        });
    }

    CompletableFuture<JsonObject> call(Body body, String expectedSession, Operation operation, JsonObject args) {
        // Do not allow callers to mutate queued arguments after validation.
        var arguments = args == null ? new JsonObject() : args.deepCopy();
        if (scriptWait(operation, arguments))
            return schedule(expectedSession, current -> awaitScript(current, body, arguments)).thenCompose(Function.identity());
        if (operation == Operation.POV) {
            return schedule(expectedSession, current -> { body(current, body); return UUID.fromString(body.entityUuid()); })
                .thenCompose(id -> pov.capture(id,expectedSession,POV));
        }
        return schedule(expectedSession, current -> executeOperation(current, body, operation, arguments));
    }

    private static boolean scriptWait(Operation operation, JsonObject args) {
        return operation == Operation.SCRIPT && args.has("operation")
            && args.get("operation").getAsString().equals("awaitAction");
    }

    private CompletableFuture<JsonObject> awaitScript(MinecraftServer current, Body body, JsonObject args) {
        var controller = actions(body, body(current, body));
        controller.requireScript(string(args, "scriptId", 80));
        return controller.awaitAction(string(args, "id", 80));
    }

    private CompletableFuture<JsonObject> streamScript(MinecraftServer current, Body body, JsonObject args, ScriptStream stream) {
        var mob = body(current, body);
        var controller = actions(body, mob);
        controller.requireScript(string(args, "scriptId", 80));
        if (!string(args, "operation", 40).equals("stream")) throw error("invalid_script_stream_operation");
        var snapshots = new ScriptSnapshot();
        return controller.stream(stream, () -> snapshots.frame(scriptSnapshot(current, mob, controller)));
    }

    private JsonObject scriptSnapshot(MinecraftServer current, Mob mob, AgentActions controller) {
        var snapshot = observe(current, mob, new JsonObject());
        var level = (ServerLevel) mob.level();
        var worldState = new JsonObject();
        // Decimal strings preserve all signed-long bits through JSON/QuickJS.
        worldState.addProperty("dayTime", Long.toString(level.getDayTime()));
        worldState.addProperty("gameTime", Long.toString(level.getGameTime()));
        worldState.addProperty("doDaylightCycle", level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT));
        worldState.addProperty("isRaining", level.isRaining());
        worldState.addProperty("rainState", level.getRainLevel(1.0F));
        // getThunderLevel multiplies by rain; it is not the raw protocol value.
        worldState.addProperty("thunderState", level.thunderLevel);
        worldState.addProperty("difficulty", level.getDifficulty().getKey());
        worldState.addProperty("hardcore", current.isHardcore());
        worldState.addProperty("levelType", level.isFlat() ? "flat" : "default");
        worldState.addProperty("maxPlayers", current.getPlayerList().getMaxPlayers());
        worldState.addProperty("serverViewDistance", current.getPlayerList().getViewDistance());
        var spawn = level.getSharedSpawnPos();
        worldState.add("spawnPoint", JsonState.object("x", spawn.getX(), "y", spawn.getY(), "z", spawn.getZ()));
        snapshot.add("worldState", worldState);
        snapshot.add("players", ScriptEntities.players(level));
        if (scriptScoreboard == null || scriptScoreboard.source != current.getScoreboard())
            scriptScoreboard = new ScriptScoreboard(current.getScoreboard());
        snapshot.add("scoreboard", scriptScoreboard.snapshot(level));
        snapshot.add("bossBars", ScriptBossBars.snapshot(level, mob, controller.hands, snapshot.getAsJsonArray("entities")));
        var items = new ScriptItems(level);
        snapshot.add("hands", controller.hands.scriptSnapshot(items));
        snapshot.add("messages", controller.drainMessages());
        snapshot.add("entityEvents", controller.drainEntityEvents());
        snapshot.add("sounds", controller.drainSounds());
        ScriptEntities.enrich(mob, snapshot.getAsJsonObject("body"), items);
        for (var value : snapshot.getAsJsonArray("entities")) {
            var observed = value.getAsJsonObject();
            var entity = level.getEntity(observed.get("id").getAsInt());
            if (entity != null) ScriptEntities.enrich(entity, observed, items);
        }
        snapshot.add("nativeBody", ScriptNavigation.capabilities(mob));
        snapshot.add("action", controller.status(""));
        snapshot.add("blocks", ScriptSnapshot.blocks((ServerLevel) mob.level(), mob.blockPosition()));
        snapshot.addProperty("minY", mob.level().getMinBuildHeight());
        snapshot.addProperty("height", mob.level().getHeight());
        snapshot.addProperty("revision", controller.nextSnapshotRevision());
        snapshot.addProperty("completedActionSequence", controller.completedActionSequence());
        return snapshot;
    }

    private JsonObject executeOperation(MinecraftServer current, Body body, Operation operation, JsonObject arguments) {
        var mob = body(current, body);
        var controller = actions(body,mob);
        return switch (operation) {
            case OBSERVE -> {
                var observation = observe(current,mob,arguments);
                observation.add("hands",controller.hands.snapshot());
                cacheBody(body,mob);
                observation.add("settings",settings(body));
                observation.add("action",controller.status(""));
                yield observation;
            }
            case ACTION -> controller.start(arguments);
            case ACTION_STATUS -> controller.status(arguments.has("id") ? string(arguments,"id",80) : "");
            case CANCEL -> {
                var result = controller.cancel(arguments.has("id") ? string(arguments,"id",80) : "");
                if (!arguments.has("id") || !controller.busy()) controller.releaseScript();
                yield result;
            }
            case BLOCKS -> blocks((ServerLevel) mob.level(), arguments);
            case COMMAND -> { controller.requireUnscripted(); yield command(current, mob, arguments); }
            case SCRIPT -> script(current, body, mob, controller, arguments);
            case POV -> throw error("pov_requires_client_renderer");
        };
    }

    private JsonObject script(MinecraftServer current, Body body, Mob mob, AgentActions controller, JsonObject args) {
        String id = string(args, "scriptId", 80);
        String operation = string(args, "operation", 40);
        if (operation.equals("end")) { controller.endScript(id); return new JsonObject(); }
        if (operation.equals("begin")) {
            int timeout = args.get("timeoutMs").getAsInt();
            controller.claimScript(id, timeout);
        } else controller.requireScript(id);
        return switch (operation) {
            case "begin", "snapshot" -> {
                var snapshot = scriptSnapshot(current, mob, controller);
                if (operation.equals("begin")) {
                    snapshot.add("itemRegistries", ScriptItems.registries((ServerLevel) mob.level()));
                    snapshot.add("chatFormattingById", ScriptEntities.chatFormatting((ServerLevel) mob.level()));
                }
                yield snapshot;
            }
            case "heartbeat" -> new JsonObject();
            case "action" -> controller.startScriptAction(args.getAsJsonObject("action"));
            case "status" -> controller.status(args.has("id") ? string(args, "id", 80) : "");
            case "cancel" -> controller.cancel(args.has("id") ? string(args, "id", 80) : "");
            case "stopRoute" -> controller.stopRoute(string(args, "id", 80));
            default -> throw error("unknown_script_operation");
        };
    }

    /** Server thread only. */
    static JsonObject behaviors(Mob mob) {
        String saved = mob.getPersistentData().getString("too_many_agents_behaviors");
        return saved.isBlank() ? new JsonObject() : com.google.gson.JsonParser.parseString(saved).getAsJsonObject();
    }

    /** Server thread: the body's agent state, or idle with no project before AgentService has loaded. */
    AgentState agentState(Mob mob) {
        var state = agentStates.get(mob.getPersistentData().getString("too_many_agents_agent"));
        return state == null ? new AgentState("", "idle", false) : state;
    }

    /** Server thread: the box confining this body (its station, else its project box), or null. */
    BodyBox box(Mob mob) {
        String agentId = mob.getPersistentData().getString("too_many_agents_agent");
        return WorldState.get(mob.getServer()).box(agentId, agentState(mob).projectId(), mob.level().dimension().location().toString());
    }

    /** Called on the server's EntityJoinLevelEvent, before a restored body can run ordinary AI. */
    void restoreBody(Entity entity) {
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)
            || mob.getPersistentData().getString("too_many_agents_agent").isBlank()) return;
        publishedWorld = new PublishedWorld(world(level.getServer()), worldSession.get());
        mob.setNoAi(true);
        mob.setCanPickUpLoot(false);
        mob.setTarget(null);
        mob.setPersistenceRequired();
        mob.setInvulnerable(true);
        behaviors(mob);
    }

    /** Called every ServerTick.Post. No references to live entities escape this method. */
    void tick(MinecraftServer current, Supplier<Set<String>> registry, Supplier<Map<String, AgentState>> states,
              BiConsumer<String, Body> observed) {
        if (server.get() != current || worldSession.get() == null) return;
        agentStates = states.get();
        worldInfo = WorldState.get(current).snapshot();
        publishedWorld = new PublishedWorld(world(current), worldSession.get());
        drainActionStops();
        Set<String> retainedAgents = registry == null ? null : registry.get();
        if (!Objects.equals(cleanupSession,worldSession.get()) || failedBodyCleanup == null) {
            cleanupSession = worldSession.get();
            failedBodyCleanup = new HashSet<>();
        }
        var seen = new HashSet<Body>();
        String world = world(current);
        for (var level : current.getAllLevels()) {
            // Actions add/remove entities (fishing hooks, loot, orphaned bodies).
            // Snapshot bodies before running them; the live entity iterator is not mutation-safe.
            var bodies = new ArrayList<Mob>();
            for (var entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && !mob.getPersistentData().getString("too_many_agents_agent").isBlank())
                    bodies.add(mob);
            }
            for (var mob : bodies) {
                if (!mob.isAlive() || mob.isRemoved()) continue;
                if (world.startsWith("unresolved:")) { mob.setNoAi(true); mob.setDeltaMovement(Vec3.ZERO); continue; }
                var saved = mob.getPersistentData();
                if (!saved.getString("too_many_agents_world").equals(world)) {
                    if (!WorldState.get(current).copiedFrom().isBlank() && saved.getString("too_many_agents_world").equals(WorldState.get(current).copiedFrom())) {
                        saved.putString("too_many_agents_world",world);
                    } else {
                    // A copied body must not run its original world's agent controls.
                    saved.putString("too_many_agents_unassigned_agent",saved.getString("too_many_agents_agent"));
                    saved.remove("too_many_agents_agent");
                    mob.setNoAi(true); mob.setInvulnerable(false); mob.setDeltaMovement(Vec3.ZERO);
                    mob.setCustomName(mob.getName().copy().append(" (unassigned)"));
                    continue;
                    }
                }
                // Once records are loaded, remove bodies no longer retained by this world.
                if (retainedAgents != null && !retainedAgents.contains(saved.getString("too_many_agents_agent"))) {
                    if (!failedBodyCleanup.contains(mob.getUUID())) {
                        try { removeBody(mob,current); }
                        catch (RuntimeException | LinkageError failure) {
                            failedBodyCleanup.add(mob.getUUID());
                            LOG.warn("Could not clean up orphaned agent body {}; not automatically retrying",mob.getStringUUID(),failure);
                        }
                    }
                    continue;
                }
                if (saved.getBoolean("too_many_agents_removing")) continue;
                var body = new Body(mob.getStringUUID(), world, level.dimension().location().toString());
                observed.accept(saved.getString("too_many_agents_agent"), body);
                seen.add(body);
                try {
                    mob.setNoAi(true);
                    if (!paused.getAsBoolean() && level.isPositionEntityTicking(mob.blockPosition())) {
                        var controller = actions(body,mob);
                        drainActionStops();
                        boolean wasBusy = controller.busy();
                        controller.tick(agentState(mob).minecraftAccess());
                        if (!wasBusy && !controller.scripted()) idle(current,mob,controller);
                        cacheBody(body,mob);
                    }
                } catch (RuntimeException | LinkageError failure) {
                    mob.setNoAi(true);
                    actions(body,mob).ambient.failed = true;
                    mob.setDeltaMovement(Vec3.ZERO);
                    LOG.warn("Movement stopped for agent body {}", mob.getStringUUID(), failure);
                    var id = localPlayer.get();
                    var player = id == null ? null : current.getPlayerList().getPlayer(id);
                    if (player != null) player.sendSystemMessage(Component.literal("Movement stopped for "
                        + mob.getName().getString() + ": its movement controller failed. See the game log."));
                }
            }
        }
        var unloaded = actions.keySet().stream().filter(ref -> !seen.contains(ref)).toList();
        for (var ref : unloaded) { actions.remove(ref).close("body_unloaded"); bodySnapshots.remove(ref); }
        drainTools(current);
    }

    /** Physical actions take precedence; otherwise run the current activity's behavior. */
    private void idle(MinecraftServer current, Mob mob, AgentActions controller) {
        if (controller.ambient.failed) { stopFollowingMotion(mob); return; }
        try {
            var behavior = behaviors(mob).get(agentState(mob).activity());
            boolean follows = behavior instanceof JsonObject b && "follow".equals(b.has("type") ? b.get("type").getAsString() : "");
            // Following leaves the station, but remains inside the project's bounds.
            var box = follows
                ? WorldState.get(current).box("", agentState(mob).projectId(), mob.level().dimension().location().toString())
                : box(mob);
            if (controller.ambient.returnInside(box)) return;
            var id = localPlayer.get();
            controller.ambient.tick(box, behavior, id == null ? null : current.getPlayerList().getPlayer(id));
        } catch (RuntimeException | LinkageError failure) {
            // Behaviors are cosmetic: stop them for this body rather than failing every tick.
            controller.ambient.failed = true;
            stopFollowingMotion(mob);
            LOG.warn("Behaviors stopped for agent body {}", mob.getStringUUID(), failure);
        }
    }

    static void follow(Mob mob, BodyBox box, ServerPlayer owner) {
        var current = mob.getServer();
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.level() != mob.level()
            || mob.isPassenger() || mob.isVehicle() || mob.isLeashed()) {
            stopFollowingMotion(mob);
            travelFollowingBody(mob, box);
            return;
        }
        var level = (ServerLevel) mob.level();
        // A confined body follows to the spot in its box nearest the player and waits there.
        boolean confined = box != null && !box.holds(owner.position());
        var goal = confined ? inside(mob, box, owner.position()) : owner.position();
        // Like pets, catch up from twelve blocks away without needing a navigable route.
        if (mob.position().distanceToSqr(goal) >= 12 * 12 && current.getTickCount() % 10 == 0
            && teleportNear(mob, BlockPos.containing(goal), box)) return;
        if (mob.position().distanceToSqr(goal) > 64 * 64) {
            stopFollowingMotion(mob);
            return;
        }
        // Leave room for the next physics step and never navigate into an unloaded chunk.
        if (!loaded(level, mob.getBoundingBox().inflate(2)) || !level.hasChunkAt(BlockPos.containing(goal))) {
            stopFollowingMotion(mob);
            return;
        }
        var navigation = mob.getNavigation();
        mob.getLookControl().setLookAt(owner, 30, 30);
        if (mob.distanceToSqr(owner) <= 9 || confined && mob.position().distanceToSqr(goal) <= 1) {
            stopFollowingMotion(mob);
            mob.getLookControl().tick();
            travelFollowingBody(mob, box);
            return;
        }
        if (mob.tickCount % 10 == 0) {
            // Vanilla PathNavigationRegion uses getChunkNow, so pathfinding never loads chunks.
            var path = confined ? navigation.createPath(BlockPos.containing(goal), 0) : navigation.createPath(owner, 1);
            if (path == null || !staysInside(path, box) || !navigation.moveTo(path, 1.0)) stopFollowingMotion(mob);
        }
        var path = navigation.getPath();
        if (path != null && !path.isDone() && !level.hasChunkAt(path.getNextNodePos())) {
            stopFollowingMotion(mob);
            return;
        }
        navigation.tick();
        mob.getMoveControl().tick();
        mob.getLookControl().tick();
        mob.getJumpControl().tick();
        travelFollowingBody(mob, box);
    }

    private static boolean teleportNear(Mob mob, BlockPos center, BodyBox confined) {
        var level = (ServerLevel) mob.level();
        var random = mob.getRandom();
        for (int attempt = 0; attempt < 10; attempt++) {
            int dx = random.nextIntBetweenInclusive(-3, 3), dz = random.nextIntBetweenInclusive(-3, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) continue;
            var pos = center.offset(dx, random.nextIntBetweenInclusive(-1, 1), dz);
            if (confined != null && !confined.holdsFeet(pos)) continue;
            var target = Vec3.atBottomCenterOf(pos);
            var box = mob.getBoundingBox().move(target.subtract(mob.position()));
            if (!loaded(level, box.inflate(1)) || !level.getWorldBorder().isWithinBounds(box)
                || box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) continue;
            if (net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(mob, pos)
                != net.minecraft.world.level.pathfinder.PathType.WALKABLE) continue;
            if (!(mob.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.FlyingPathNavigation)
                && level.getBlockState(pos.below()).getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) continue;
            if (!level.noCollision(mob, box) || level.containsAnyLiquid(box)
                || !level.getEntities(mob, box, Entity::isAlive).isEmpty()) continue;
            stopFollowingMotion(mob);
            mob.moveTo(target.x, target.y, target.z, mob.getYRot(), mob.getXRot());
            mob.setDeltaMovement(Vec3.ZERO);
            mob.fallDistance = 0;
            mob.setOnGround(true);
            return true;
        }
        return false;
    }

    /** Move a body one physics step. The step is undone if it would leave its box, or move further out of it. */
    static void travelFollowingBody(Mob mob, BodyBox box) {
        if (mob.isPassenger() || mob.isSleeping()) return; // Native riding/sleep owns its position.
        var before = mob.position();
        // NoAI disables physics as well as goals. Enable only travel(), never a mob/brain AI tick.
        mob.setNoAi(false);
        try {
            mob.travel(new Vec3(mob.xxa, mob.yya, mob.zza));
        } finally {
            mob.setNoAi(true);
        }
        if (box != null && outside(box, mob.position()) > outside(box, before) + 0.001) {
            mob.setPos(before);
            mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
            mob.getNavigation().stop();
        }
    }

    /** How far a body position is outside its box, ignoring depth below it so the body can still fall. */
    private static double outside(BodyBox box, Vec3 position) {
        var inside = box.clamp(position);
        return Math.hypot(Math.hypot(position.x - inside.x, position.z - inside.z), Math.max(0, position.y - inside.y));
    }

    /** The target itself if a body there would be inside the box, else the nearest standing spot inside it. */
    static Vec3 inside(Mob mob, BodyBox box, Vec3 target) {
        if (box == null || box.holds(target)) return target;
        var spot = box.clamp(BlockPos.containing(target));
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
            var pos = spot.offset(0, dy, 0);
            if (box.holdsFeet(pos) && mob.level().hasChunkAt(pos) && net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(mob, pos)
                == net.minecraft.world.level.pathfinder.PathType.WALKABLE) return Vec3.atBottomCenterOf(pos);
        }
        return Vec3.atBottomCenterOf(spot);
    }

    /** Whether a path, once inside the box, never leaves it. A path may start outside to return a body. */
    static boolean staysInside(net.minecraft.world.level.pathfinder.Path path, BodyBox box) {
        if (box == null) return true;
        boolean entered = false;
        for (int i = 0; i < path.getNodeCount(); i++) {
            boolean in = box.holdsFeet(path.getNodePos(i));
            if (entered && !in) return false;
            entered |= in;
        }
        return true;
    }

    static void stopFollowingMotion(Mob mob) {
        mob.stopInPlace();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.getJumpControl().tick();
        mob.setJumping(false);
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    /** Drop belongings, then save the empty body for restoration after an archive. */
    CompletableFuture<String> saveBody(Body ref, String session) {
        return schedule(session, current -> {
            var mob = body(current, ref);
            var controller = actions(ref, mob);
            // Freeze the body while its snapshot is written, including across a failed disk save.
            mob.getPersistentData().putBoolean("too_many_agents_removing", true);
            stopFollowingMotion(mob);
            actions.remove(ref);
            controller.close("thread_archived");
            dropInventory(mob, controller.hands);
            var tag = new net.minecraft.nbt.CompoundTag();
            if (!mob.save(tag)) throw error("body_save_failed");
            return tag.toString();
        });
    }

    CompletableFuture<Void> suspendBody(Body ref, String saved, String session) {
        return schedule(session, current -> {
            var level = savedBodyLevel(current, ref, saved);
            var entity = level.getEntity(UUID.fromString(ref.entityUuid()));
            if (entity == null) return null;
            var controller = actions.remove(ref);
            if (controller != null) controller.close("thread_archived");
            entity.getPersistentData().putBoolean("too_many_agents_removing", true);
            entity.discard();
            bodySnapshots.remove(ref);
            return null;
        });
    }

    CompletableFuture<Void> restoreSavedBody(Body ref, String saved, String session) {
        return schedule(session, current -> {
            var level = savedBodyLevel(current, ref, saved);
            var existing = level.getEntity(UUID.fromString(ref.entityUuid()));
            if (existing instanceof Mob mob) {
                mob.getPersistentData().remove("too_many_agents_removing");
                cacheBody(ref,mob);
                return null;
            }
            try {
                var entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(net.minecraft.nbt.TagParser.parseTag(saved),level,java.util.function.Function.identity());
                if (!(entity instanceof Mob mob)) throw error("invalid_saved_body");
                mob.getPersistentData().remove("too_many_agents_removing");
                mob.getPersistentData().putString("too_many_agents_world",world(current));
                if (!level.addFreshEntity(mob)) throw error("body_restore_rejected");
                restoreBody(mob); cacheBody(ref,mob);
                return null;
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw error("invalid_saved_body"); }
        });
    }

    private ServerLevel savedBodyLevel(MinecraftServer current, Body ref, String saved) {
        if (!worldMatches(ref.world(),world(current))) throw error("body_world_mismatch");
        var key = ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(ref.dimension()));
        var level = current.getLevel(key);
        if (level == null) throw error("body_dimension_unavailable");
        try {
            var position = net.minecraft.nbt.TagParser.parseTag(saved).getList("Pos",net.minecraft.nbt.Tag.TAG_DOUBLE);
            level.getChunk((int)Math.floor(position.getDouble(0)) >> 4,(int)Math.floor(position.getDouble(2)) >> 4);
            return level;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw error("invalid_saved_body"); }
    }

    /** Remove a loaded agent body, retaining every remaining inventory stack as a world item. */
    CompletableFuture<Void> removeAgent(Body ref, String expectedSession, String agentId) {
        return removeAgent(ref, expectedSession, agentId, false);
    }

    CompletableFuture<Void> removeAgent(Body ref, String expectedSession, String agentId, boolean knownRemoved) {
        return removeAgent(ref,expectedSession,agentId,knownRemoved,null,Long.MAX_VALUE);
    }

    CompletableFuture<Void> removeAgent(Body ref, String expectedSession, String agentId, boolean knownRemoved, ToolScope scope, long expiresAt) {
        return schedule(expectedSession, expiresAt, current -> {
            synchronized(queueLock) { if(scope!=null && scope.closed!=null) throw error(scope.closed); }
            if (ref == null || !worldMatches(ref.world(), world(current))) throw error("body_world_mismatch");
            var bodies = new ArrayList<Mob>();
            for (var level : current.getAllLevels()) for (var entity : level.getAllEntities()) {
                if (entity instanceof Mob mob && !mob.isRemoved()
                    && agentId.equals(mob.getPersistentData().getString("too_many_agents_agent"))
                    && (mob.getPersistentData().getString("too_many_agents_world").isBlank()
                        || worldMatches(mob.getPersistentData().getString("too_many_agents_world"),world(current)))) bodies.add(mob);
            }
            if (bodies.isEmpty() && !knownRemoved) throw error("body_missing_or_unloaded");
            for (var mob : bodies) removeBody(mob, current);
            return null;
        });
    }

    private void removeBody(Mob mob, MinecraftServer current) {
        var ref = new Body(mob.getStringUUID(), world(current), mob.level().dimension().location().toString());
        var controller = actions(ref, mob);
        mob.getPersistentData().putBoolean("too_many_agents_removing", true);
        stopFollowingMotion(mob);
        actions.remove(ref);
        bodySnapshots.remove(ref);
        controller.close("agent_removed");
        dropInventory(mob, controller.hands);
        mob.discard();
        if (!mob.isRemoved()) throw error("body_removal_not_confirmed");
    }

    private void dropInventory(Mob mob, AgentHands hands) {
        var inventory = hands.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            var drop = new net.minecraft.world.entity.item.ItemEntity(mob.level(), mob.getX(), mob.getY() + 0.25, mob.getZ(), stack.copy());
            drop.setDefaultPickUpDelay();
            if (!((ServerLevel) mob.level()).addFreshEntity(drop)) throw error("inventory_drop_rejected_retry_removal");
            inventory.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
            hands.save();
        }
    }

    /** Roll back a newly created body when agent creation fails. */
    CompletableFuture<Void> remove(Body body, String expectedSession) {
        return schedule(expectedSession, current -> {
            var controller = actions.remove(body);
            if (controller != null) controller.close("body_removed");
            bodySnapshots.remove(body);
            body(current, body).discard();
            return null;
        });
    }

    private <T> CompletableFuture<T> schedule(String expectedSession, Function<MinecraftServer, T> work) {
        return schedule(expectedSession,Long.MAX_VALUE,work);
    }

    private <T> CompletableFuture<T> schedule(String expectedSession, long expiresAt, Function<MinecraftServer, T> work) {
        var current = server.get();
        if (current == null || expectedSession == null || !expectedSession.equals(worldSession.get())) {
            return CompletableFuture.failedFuture(error("world_session_changed"));
        }
        if (paused.getAsBoolean()) return CompletableFuture.failedFuture(error("game_paused"));
        var result = new CompletableFuture<T>();
        var state = new AtomicReference<>("queued");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUEUE_SECONDS);
        // Expiration only cancels work that has not started. Running mutations never receive a false timeout.
        CompletableFuture.delayedExecutor(QUEUE_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (state.compareAndSet("queued", "expired")) result.completeExceptionally(error("expired_before_execution"));
        });
        try {
            current.execute(() -> {
                if (!state.compareAndSet("queued", "running")) return;
                if (result.isCancelled()) return;
                try {
                    if (System.nanoTime() > deadline || System.currentTimeMillis() >= expiresAt) throw error("expired_before_execution");
                    if (server.get() != current || !expectedSession.equals(worldSession.get())) throw error("world_session_changed");
                    if (paused.getAsBoolean()) throw error("game_paused");
                    result.complete(work.apply(current));
                } catch (Exception | LinkageError exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            state.set("rejected");
            result.completeExceptionally(exception);
        }
        return result;
    }

    private static String world(MinecraftServer server) {
        return WorldState.get(server).activeId();
    }

    private ServerPlayer player(MinecraftServer server) {
        var id = localPlayer.get();
        var player = id == null ? null : server.getPlayerList().getPlayer(id);
        if (player == null) throw error("local_player_unavailable");
        return player;
    }

    private static Mob body(MinecraftServer server, Body body) { return body(server, body, false); }

    private static Mob body(MinecraftServer server, Body body, boolean allowRemoving) {
        if (body == null || !Objects.equals(body.world(), world(server))) throw error("body_world_mismatch");
        var id = ResourceLocation.tryParse(body.dimension());
        if (id == null) throw error("invalid_body_dimension");
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) throw error("body_dimension_unavailable");
        var entity = level.getEntity(UUID.fromString(body.entityUuid()));
        if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved()) throw error("body_missing_or_unloaded");
        if (mob.getPersistentData().getString("too_many_agents_agent").isBlank()) throw error("entity_is_not_an_agent_body");
        if (!allowRemoving && mob.getPersistentData().getBoolean("too_many_agents_removing")) throw error("agent_body_removal_pending");
        return mob;
    }

    static void placeNear(ServerLevel level, Entity anchor, Mob mob, BodyBox confined) { placeNear(level, anchor, null, mob, confined); }

    /** Searches outward from target when given, otherwise from a little way around the anchor. */
    static void placeNear(ServerLevel level, Entity anchor, BlockPos target, Mob mob, BodyBox confined) {
        // Inside a box, search outward from the box point nearest the start; a 1x1 station has one spot.
        var start = target != null ? target : anchor.blockPosition();
        var origin = confined == null ? start : confined.clamp(start);
        for (int radius = confined == null && target == null ? 2 : 0; radius <= 12; radius++) {
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6}) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        var pos = origin.offset(dx, dy, dz);
                        if (confined != null && !confined.holdsFeet(pos)) continue;
                        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, anchor.getYRot(), 0);
                        var box = mob.getBoundingBox();
                        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()) continue;
                        if (!loaded(level, box) || !level.getWorldBorder().isWithinBounds(box)) continue;
                        if (!level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below())) continue;
                        if (!level.noCollision(mob) || level.containsAnyLiquid(box)) continue;
                        if (!level.getEntities(mob, box, Entity::isAlive).isEmpty()) continue;
                        return;
                    }
                }
            }
        }
        throw error(confined != null ? "no_clear_spawn_space_in_box" : anchor instanceof ServerPlayer ? "no_clear_spawn_space_near_player" : "no_clear_spawn_space_near_agent");
    }

    private static boolean loaded(ServerLevel level, AABB box) {
        return level.hasChunksAt(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ));
    }

    private JsonObject observe(MinecraftServer current, Mob mob, JsonObject args) {
        double radius = args.has("radius") ? number(args.get("radius"), "radius") : OBSERVE_RADIUS;
        if (radius < 1 || radius > 64) throw error("radius_must_be_between_1_and_64");
        var result = new JsonObject();
        result.addProperty("session", worldSession.get());
        result.addProperty("world", world(current));
        result.addProperty("dimension", mob.level().dimension().location().toString());
        result.addProperty("tick", current.getTickCount());
        result.addProperty("timeOfDay", mob.level().getDayTime());
        result.add("body", Observations.entity(mob));
        var box = box(mob);
        result.add("box", box == null ? com.google.gson.JsonNull.INSTANCE : box.json());
        result.add("localPlayer", Observations.entity(player(current)));
        result.addProperty("localPlayerDimension", player(current).level().dimension().location().toString());
        result.addProperty("radius", radius);
        var capabilities = new JsonObject();
        var availableActions = new JsonArray();
        var mode = BodySettings.mode(mob.getPersistentData().getString("too_many_agents_mode"));
        AgentActions.TYPES.stream().filter(type -> mode.creative || !type.equals("creative_item")).forEach(availableActions::add);
        capabilities.add("physicalActions",availableActions);
        capabilities.addProperty("commandEditing",mode.commands);
        capabilities.addProperty("povImages",true);
        capabilities.addProperty("navigationRadius",64);
        capabilities.addProperty("damageAndHunger",false);
        result.add("capabilities",capabilities);
        var nearby = mob.level().getEntities(mob, mob.getBoundingBox().inflate(radius), entity -> entity.isAlive() && mob.distanceToSqr(entity) <= radius * radius);
        nearby.sort(Comparator.comparingDouble(mob::distanceToSqr));
        var entities = new JsonArray();
        nearby.stream().limit(ENTITY_LIMIT).forEach(entity -> entities.add(Observations.entity(entity)));
        result.add("entities", entities);
        result.addProperty("entitiesTruncated", nearby.size() > ENTITY_LIMIT);
        var from = mob.getEyePosition();
        var to = from.add(mob.getLookAngle().scale(LOOK_DISTANCE));
        var target = new JsonObject();
        if (!loaded((ServerLevel) mob.level(), new AABB(from, to))) {
            target.addProperty("kind", "unloaded");
        } else {
            var hit = mob.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mob));
            if (hit.getType() == HitResult.Type.BLOCK) {
                target.addProperty("kind", "block");
                target.add("position", Observations.coordinates(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ()));
                target.addProperty("block", BuiltInRegistries.BLOCK.getKey(mob.level().getBlockState(hit.getBlockPos()).getBlock()).toString());
                target.addProperty("face", hit.getDirection().getName());
            } else target.addProperty("kind", "miss");
        }
        result.add("bodyLookTarget", target);
        return result;
    }

    private static JsonObject blocks(ServerLevel level, JsonObject args) {
        var min = coordinates(args, "min");
        var max = coordinates(args, "max");
        long dx = (long) max.getX() - min.getX() + 1;
        long dy = (long) max.getY() - min.getY() + 1;
        long dz = (long) max.getZ() - min.getZ() + 1;
        if (dx <= 0 || dy <= 0 || dz <= 0) throw error("min_must_not_exceed_max");
        if (dx > 4096 || dy > 4096 || dz > 4096 || dx * dy * dz > 4096) throw error("block_box_exceeds_4096_blocks");
        if (min.getY() < level.getMinBuildHeight() || max.getY() >= level.getMaxBuildHeight()) {
            throw error("y_outside_build_height: " + level.getMinBuildHeight() + ".." + (level.getMaxBuildHeight() - 1));
        }
        if (!level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max)) throw error("block_box_outside_world_border");
        if (!level.hasChunksAt(min, max)) throw error("block_box_contains_unloaded_chunks");
        if (args.has("includeAir") && (!args.get("includeAir").isJsonPrimitive()
            || !args.getAsJsonPrimitive("includeAir").isBoolean())) throw error("includeAir_must_be_a_boolean");
        boolean includeAir = args.has("includeAir") && args.get("includeAir").getAsBoolean();
        var found = new JsonArray();
        for (var pos : BlockPos.betweenClosed(min, max)) {
            var state = level.getBlockState(pos);
            if (!includeAir && state.isAir()) continue;
            var block = new JsonObject();
            block.addProperty("x", pos.getX());
            block.addProperty("y", pos.getY());
            block.addProperty("z", pos.getZ());
            block.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            block.addProperty("state", state.toString());
            found.add(block);
        }
        var result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("volume", dx * dy * dz);
        result.addProperty("includeAir", includeAir);
        result.add("blocks", found);
        return result;
    }

    static JsonObject chatRecord(ServerLevel level, Component message, String position, UUID sender) {
        var result = new JsonObject();
        result.add("message", net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(
            level.registryAccess().createSerializationContext(JsonOps.INSTANCE), message).getOrThrow());
        result.addProperty("position", position);
        if (sender != null) result.addProperty("sender", sender.toString());
        return result;
    }

    void soundEvent(net.neoforged.neoforge.event.PlayLevelSoundEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() != server.get()
            || !level.getServer().isSameThread()) return;
        Vec3 position;
        if (event instanceof net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition at) position = at.getPosition();
        else if (event instanceof net.neoforged.neoforge.event.PlayLevelSoundEvent.AtEntity at) position = at.getEntity().position();
        else return;
        for (var controller : actions.values()) {
            if (controller.scripted() && controller.mob.level() == level) controller.recordSound(event, position);
        }
    }

    void entityEvent(String kind, Entity subject, Entity cause, ItemStack originalItem) {
        entityEvent(kind, subject, cause, originalItem, () -> true);
    }

    void deathEvent(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        entityEvent("entityDead", event.getEntity(), null, null, () -> !event.isCanceled());
    }

    private void entityEvent(String kind, Entity subject, Entity cause, ItemStack originalItem,
                             java.util.function.BooleanSupplier accepted) {
        if (!(subject.level() instanceof ServerLevel level) || level.getServer() != server.get()) return;
        if (!level.getServer().isSameThread()) return;
        if (subject instanceof AgentHands hands) subject = hands.visibleBody();
        if (cause instanceof AgentHands hands) cause = hands.visibleBody();
        for (var controller : actions.values()) {
            if (!controller.scripted() || controller.mob.level() != level || controller.mob.distanceToSqr(subject) > OBSERVE_RADIUS * OBSERVE_RADIUS) continue;
            try {
                var event = new JsonObject(); event.addProperty("name", kind);
                var entities = new JsonArray();
                var items = new ScriptItems(level);
                var primary = Observations.entity(subject);
                ScriptEntities.enrich(subject, primary, items);
                entities.add(primary);
                event.addProperty("subject", subject.getId());
                if (cause != null && controller.mob.distanceToSqr(cause) <= OBSERVE_RADIUS * OBSERVE_RADIUS) {
                    event.addProperty("cause", cause.getId());
                    var secondary = Observations.entity(cause);
                    ScriptEntities.enrich(cause, secondary, items);
                    if (originalItem != null) secondary.add("droppedItem", JsonState.object("wire", items.wire(originalItem)));
                    entities.add(secondary);
                }
                event.add("entities", entities);
                controller.recordEntityEvent(event, accepted);
            } catch (RuntimeException failure) {
                // Observation errors terminate only this lease, not native gameplay.
                controller.failObservation("script_entity_event_serialization_failed");
            }
        }
    }

    void publicChat(ServerPlayer sender, Component message) {
        if (sender.getServer() != server.get()) return;
        if (!sender.getServer().isSameThread()) throw error("chat_requires_server_thread");
        var formatted = Component.translatable("chat.type.text", sender.getDisplayName(), message);
        broadcastScriptChat(chatRecord(sender.serverLevel(), formatted, "chat", sender.getUUID()));
    }

    private void broadcastScriptChat(JsonObject message) {
        for (var controller : actions.values()) controller.recordMessage(message);
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> tabComplete(Mob mob, JsonObject args) {
        String text = string(args, "text", 4096);
        if (text.codePoints().anyMatch(c -> c < 32 || c == 127)) throw error("invalid_completion_text");
        var current = mob.getServer();
        var source = player(current).createCommandSourceStack().withSuppressedOutput()
            .withEntity(mob).withLevel((ServerLevel) mob.level()).withPosition(mob.position()).withRotation(mob.getRotationVector());
        if (!BodySettings.mode(mob.getPersistentData().getString("too_many_agents_mode")).commands)
            source = source.withPermission(0);
        var reader = new com.mojang.brigadier.StringReader(text);
        if (reader.canRead() && reader.peek() == '/') reader.skip();
        var dispatcher = current.getCommands().getDispatcher();
        return dispatcher.getCompletionSuggestions(dispatcher.parse(reader, source));
    }

    private JsonObject scriptChat(Mob mob, JsonObject args) {
        String text = string(args, "message", 4096);
        if (text.isBlank() || text.codePoints().anyMatch(c -> c < 32 || c == 127 || c == 167)) throw error("invalid_chat_message");
        var current = mob.getServer();
        if ((args.has("target") || !text.startsWith("/")) && text.length() > 256) throw error("chat_message_too_long");
        if (args.has("target")) {
            String target = string(args, "target", 256);
            var recipient = current.getPlayerList().getPlayerByName(target);
            var matches = actions.values().stream().filter(a -> a.mob.getName().getString().equals(target) && a.mob.isAlive()).toList();
            if (recipient == null && matches.size() != 1 || recipient != null && !matches.isEmpty()) throw error("whisper_target_missing_or_ambiguous");
            var incoming = Component.translatable("commands.message.display.incoming", mob.getName(), Component.literal(text));
            if (recipient != null) recipient.sendSystemMessage(incoming);
            else matches.get(0).recordMessage(chatRecord((ServerLevel) mob.level(), incoming, "chat", mob.getUUID()));
            var outgoing = Component.translatable("commands.message.display.outgoing", Component.literal(target), Component.literal(text));
            var owner = actions.values().stream().filter(a -> a.mob == mob).findFirst().orElseThrow();
            owner.recordMessage(chatRecord((ServerLevel) mob.level(), outgoing, "system", null));
        } else if (text.startsWith("/")) {
            var request = new JsonObject(); request.addProperty("command", text);
            var result = command(current, mob, request);
            var owner = actions.values().stream().filter(a -> a.mob == mob).findFirst().orElseThrow();
            for (var feedback : result.getAsJsonArray("feedback"))
                owner.recordMessage(chatRecord((ServerLevel) mob.level(), Component.literal(feedback.getAsString()), "system", null));
            return result;
        } else {
            var formatted = Component.translatable("chat.type.text", mob.getName(), Component.literal(text));
            current.getPlayerList().broadcastSystemMessage(formatted, false);
            var record = chatRecord((ServerLevel) mob.level(), formatted, "chat", mob.getUUID());
            record.addProperty("verified", false); // Native body speech has no player signature.
            broadcastScriptChat(record);
        }
        return JsonState.object("status", "sent");
    }

    private JsonObject command(MinecraftServer current, Mob mob, JsonObject args) {
        if (!BodySettings.mode(mob.getPersistentData().getString("too_many_agents_mode")).commands) throw error("world_commands_disabled");
        var text = string(args, "command", 4096).strip();
        if (text.startsWith("/")) text = text.substring(1);
        if (text.isBlank() || text.contains("\n") || text.contains("\r")) throw error("invalid_command");
        var feedback = new JsonArray();
        var callbacks = new JsonArray();
        var output = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { feedback.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        // Keep the local player's permissions. @s and relative coordinates refer to the NPC body.
        var source = player(current).createCommandSourceStack().withSource(output)
            .withEntity(mob).withLevel((ServerLevel) mob.level()).withPosition(mob.position())
            .withRotation(mob.getRotationVector()).withCallback((success, value) -> {
                var callback = new JsonObject();
                callback.addProperty("success", success);
                callback.addProperty("result", value);
                callbacks.add(callback);
            });
        current.getCommands().performPrefixedCommand(source, text);
        var result = new JsonObject();
        result.add("feedback", feedback.deepCopy());
        result.add("results", callbacks.deepCopy());
        // Some commands do not call the result callback. Feedback remains authoritative; do not invent success.
        result.addProperty("status", callbacks.isEmpty() ? "no_result_reported" : "completed");
        return result;
    }

    CompletableFuture<Void> announceCommunication(Body sender, Body recipient, String session, String summary) {
        return schedule(session, current -> {
            if (!TooManyAgentsClientSettings.get().showAgentCommunication()) return null;
            var from = body(current, sender);
            var to = body(current, recipient);
            player(current).sendSystemMessage(Component.literal(from.getName().getString())
                .append(Component.literal(" → ").withStyle(style -> style.withColor(0xAAAAAA)))
                .append(Component.literal(to.getName().getString()))
                .append(Component.literal(": " + summary).withStyle(style -> style.withColor(0xDDDDDD))));
            return null;
        });
    }

    private static BlockPos coordinates(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonObject()) throw error(key + "_must_be_an_xyz_object");
        var value = args.getAsJsonObject(key);
        return new BlockPos(integer(value.get("x"), key + ".x"), integer(value.get("y"), key + ".y"), integer(value.get("z"), key + ".z"));
    }

    private static int integer(JsonElement value, String key) {
        double number = number(value, key);
        if (number != Math.rint(number) || number < -30_000_000 || number > 30_000_000) throw error(key + "_must_be_an_integer_within_world_limits");
        return (int) number;
    }

    private static double number(JsonElement value, String key) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw error(key + "_must_be_a_number");
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw error(key + "_must_be_finite");
        return number;
    }

    private static String string(JsonObject args, String key, int maxLength) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw error(key + "_must_be_a_string");
        var text = value.getAsString();
        if (text.length() > maxLength) throw error(key + "_too_long_max_" + maxLength);
        return text;
    }

    private static IllegalStateException error(String message) { return new IllegalStateException(message); }
}
