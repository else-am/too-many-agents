package toomanyagents;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import toomanyagents.ui.TooManyAgentsSettingsScreen;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Mod(value = "too_many_agents", dist = Dist.CLIENT)
public final class TooManyAgents {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson JSON = new Gson();
    private record WorldSession(MinecraftServer server, String id) {}
    private final AtomicReference<WorldSession> session = new AtomicReference<>();
    // JSON objects are completed on their owning game thread, then never mutated.
    private volatile JsonObject serverSnapshot;
    private volatile String snapshot = "{\"protocol\":1,\"state\":\"starting\"}";
    private volatile boolean paused = true;
    private volatile UUID localPlayer;
    private final ArrayDeque<String> chat = new ArrayDeque<>();
    private int clientTicks;
    private AgentService agents;
    private GameAccess game;
    private final ClientControls controls;

    private MinecraftServer currentServer() {
        var current = session.get();
        return current == null ? null : current.server();
    }

    private String currentSession() {
        var current = session.get();
        return current == null ? null : current.id();
    }

    public TooManyAgents(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mod, parent) -> TooManyAgentsSettingsScreen.open(parent));
        AgentInventoryMenu.MENUS.register(modBus);
        BodyFishingHook.ENTITIES.register(modBus);
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) ->
            event.registerEntityRenderer(BodyFishingHook.TYPE.get(), net.minecraft.client.renderer.entity.FishingHookRenderer::new));
        toomanyagents.mobs.Mobs.register(modBus);
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) ->
            event.register(AgentInventoryMenu.TYPE.get(), toomanyagents.ui.AgentInventoryScreen::new));
        controls = new ClientControls(modBus, () -> agents);
        modBus.addListener(this::setup);
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::chatReceived);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
            (net.neoforged.neoforge.event.ServerChatEvent event) -> {
                if (game != null && !event.isCanceled()) game.publicChat(event.getPlayer(), event.getMessage());
            });
        NeoForge.EVENT_BUS.addListener(this::despawn);
        NeoForge.EVENT_BUS.addListener(this::incomingDamage);
        NeoForge.EVENT_BUS.addListener((ScriptForcedMoveEvent event) -> {
            if (game != null) game.forcedMoveAttempt(event);
        });
        NeoForge.EVENT_BUS.addListener((ScriptBlockEvent event) -> {
            if (game != null) game.blockEvent(event);
        });
        NeoForge.EVENT_BUS.addListener((ScriptContainerOpenersEvent event) -> {
            if (game != null) game.containerOpeners(event);
        });
        NeoForge.EVENT_BUS.addListener((ScriptParticleEvent event) -> {
            if (game != null) game.particleEvent(event);
        });
        NeoForge.EVENT_BUS.addListener((ScriptEntitySignalEvent event) -> {
            if (game != null) game.entitySignal(event);
        });
        NeoForge.EVENT_BUS.addListener((ScriptCollectionEvent event) -> {
            if (game != null) game.collectionEvent(event);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.NORMAL, true,
            (net.neoforged.neoforge.event.PlayLevelSoundEvent event) -> {
            if (game != null) game.soundEvent(event);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) -> {
            if (game != null && !event.getEntity().level().isClientSide)
                game.entityEvent("entityHurt", event.getEntity(), event.getSource().getEntity(), null);
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.NORMAL, true,
            (net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) -> {
                if (game != null) game.deathEvent(event);
            });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) -> {
            if (game != null) game.entityEvent("playerCollect", event.getPlayer(), event.getItemEntity(), event.getOriginalStack());
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Finish event) -> {
            if (game != null && !event.getEntity().level().isClientSide) game.nativeUseFinished(event);
        });
        NeoForge.EVENT_BUS.addListener(this::entityJoined);
        NeoForge.EVENT_BUS.addListener(this::entityLeft);
        NeoForge.EVENT_BUS.addListener(this::entityChangingDimension);
    }

    private void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                var directory = FMLPaths.GAMEDIR.get().resolve("too-many-agents");
                game = new GameAccess(this::currentServer, this::currentSession, () -> localPlayer, () -> paused);
                game.setPov(new PovCapture(this::currentSession));
                agents = new AgentService(game, this::currentSession);
                var bridge = new LocalBridge(directory, () -> snapshot, this::command, this::agentRoute);
                agents.bridgeConnection(bridge.url(),bridge.token());
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    bridge.close();
                    agents.close();
                }, "too_many_agents-shutdown"));
                LOG.info("Too Many Agents local interface ready; connection details in too-many-agents/connection.json");
            } catch (IOException exception) {
                LOG.error("Too Many Agents local interface could not start", exception);
            }
        });
    }

    private void despawn(MobDespawnEvent event) {
        if (!event.getEntity().getPersistentData().getString("too_many_agents_agent").isBlank()) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    private void incomingDamage(LivingIncomingDamageEvent event) {
        // Creative attacks, the void, and /kill bypass vanilla invulnerability.
        if (!event.getEntity().getPersistentData().getString("too_many_agents_agent").isBlank()) {
            event.setCanceled(true);
        }
    }

    private void entityJoined(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && game != null) game.restoreBody(event.getEntity());
    }

    private void entityLeft(EntityLeaveLevelEvent event) {
        if (game == null || agents == null) return;
        var body = game.destroyedBody(event.getEntity());
        if (body != null) agents.bodyLost(body);
    }

    private void entityChangingDimension(EntityTravelToDimensionEvent event) {
        if (game != null) game.changingDimension(event.getEntity(), event.getDimension());
    }

    private LocalBridge.Reply agentRoute(String method, String path, JsonObject request) {
        try {
            Object result;
            if (path.equals("/v1/dev") && DevelopmentWorld.ENABLED) {
                result = method.equals("GET") ? DevelopmentChecks.snapshot() : game.development(request).get(10,TimeUnit.SECONDS);
            } else if (path.equals("/v1/bb") && method.equals("POST")) {
                if ("script".equals(field(request, "op")) && request.has("arguments")
                    && request.get("arguments").isJsonObject()
                    && "stream".equals(field(request.getAsJsonObject("arguments"), "operation"))) {
                    var stream = new ScriptStream();
                    agents.callback(request, stream).whenComplete((done, failure) -> {
                        if (failure == null) stream.finish();
                        else stream.fail(failure);
                    });
                    return new LocalBridge.Reply(200, "", stream);
                }
                // Script completion waits hold a virtual HTTP thread, never the
                // game thread. Their script deadline and scope cancellation still apply.
                boolean scriptWait = "script".equals(field(request, "op"))
                    && request.has("arguments") && request.get("arguments").isJsonObject()
                    && java.util.Set.of("awaitAction", "awaitTicks").contains(field(request.getAsJsonObject("arguments"), "operation"));
                try { result = JsonState.object("ok",true,"result",agents.callback(request).get(scriptWait ? 310 : 15,TimeUnit.SECONDS)); }
                catch(java.util.concurrent.TimeoutException failure) { return new LocalBridge.Reply(504,JSON.toJson(JsonState.object("ok",false,"error",JsonState.object("message","callback_outcome_unknown_do_not_retry")))); }
                catch(InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    return new LocalBridge.Reply(503, JSON.toJson(JsonState.object("ok",false,"error",
                        JsonState.object("code","interrupted_outcome_unknown","message","interrupted_outcome_unknown"))));
                }
                catch(Exception failure) {
                    Throwable cause = failure;
                    while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
                    boolean stale = cause instanceof AgentService.StaleSessionException;
                    var rejection = cause instanceof ScriptRequestRejection known ? known : null;
                    int status = stale ? 409 : rejection != null ? 400 : 500;
                    String code = stale ? "world_session_changed" : rejection != null ? rejection.code : "callback_failed";
                    return new LocalBridge.Reply(status, JSON.toJson(JsonState.object("ok",false,"error",
                        JsonState.object("code",code,"message",cause.getMessage()))));
                }
            } else if (path.equals("/v1/agents/providers")) result = agents.backendStatus().get(30,TimeUnit.SECONDS);
            else if (path.equals("/v1/agents/projects")) result = method.equals("GET") ? agents.projects() : agents.projectCommand(request).get(110,TimeUnit.SECONDS);
            else if (method.equals("GET") && path.equals("/v1/agents")) result = agents.list();
            else if (method.equals("GET") && path.equals("/v1/agents/catalog")) result = agents.catalog().get(100, TimeUnit.SECONDS);
            else if (method.equals("GET") && path.startsWith("/v1/agents/catalog/")) result = agents.catalog(path.substring("/v1/agents/catalog/".length())).get(100, TimeUnit.SECONDS);
            else if (method.equals("POST") && path.equals("/v1/agents/spawn")) {
                var id = agents.spawn(request).get(110, TimeUnit.SECONDS);
                var created = new JsonObject();
                created.addProperty("id", id);
                result = created;
            } else if (method.equals("POST") && path.equals("/v1/ui")) {
                String action = field(request, "action");
                result = switch (action) {
                    case "click", "hover", "text", "key", "close", "scroll" -> controls.input(request).get(10, TimeUnit.SECONDS);
                    default -> controls.inspect(request).get(10, TimeUnit.SECONDS);
                };
            } else if (path.startsWith("/v1/agents/")) {
                var parts = path.substring("/v1/agents/".length()).split("/");
                String id = parts[0];
                if (method.equals("GET") && parts.length == 1) result = agents.snapshot(id);
                else if (method.equals("POST") && parts.length == 2) {
                    result = switch (parts[1]) {
                        case "message" -> agents.send(id, request).get(100, TimeUnit.SECONDS);
                        case "cancel-queued" -> agents.cancelQueued(id, field(request, "messageId")).get(10, TimeUnit.SECONDS);
                        case "transcript" -> agents.transcript(id,request).get(10, TimeUnit.SECONDS);
                        case "inventory" -> agents.inventory(id).get(10, TimeUnit.SECONDS);
                        case "settings" -> agents.updateSettings(id,request).get(10,TimeUnit.SECONDS);
                        case "interrupt" -> agents.interrupt(id).get(100, TimeUnit.SECONDS);
                        case "remove" -> agents.remove(id, false).get(100, TimeUnit.SECONDS);
                        case "archive" -> agents.remove(id, true).get(100, TimeUnit.SECONDS);
                        case "conversation-archive" -> agents.archiveConversation(id, true).get(100, TimeUnit.SECONDS);
                        case "conversation-restore" -> agents.archiveConversation(id, false).get(100, TimeUnit.SECONDS);
                        case "respond" -> agents.respond(id, field(request, "requestId"), request.getAsJsonObject("resolution")).get(100, TimeUnit.SECONDS);
                        case "tool" -> agents.call(id, field(request, "tool"), request.getAsJsonObject("arguments")).get(10, TimeUnit.SECONDS);
                        default -> throw new IllegalArgumentException("unknown_endpoint");
                    };
                    if (result == null) result = new JsonObject();
                } else return LocalBridge.error(404, "unknown_endpoint");
            } else return LocalBridge.error(404, "unknown_endpoint");
            return new LocalBridge.Reply(200, JSON.toJson(result));
        } catch (TimeoutException exception) {
            return LocalBridge.error(504, "outcome_unknown_do_not_retry");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LocalBridge.error(503, "interrupted_outcome_unknown");
        } catch (Exception exception) {
            Throwable cause = exception;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            return LocalBridge.error(400, cause.getMessage() == null ? "agent_request_failed" : cause.getMessage());
        }
    }

    private static String field(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return "";
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException(key + "_must_be_a_string");
        }
        return object.get(key).getAsString();
    }

    private static boolean flag(JsonObject object, String key) {
        if (!object.has(key)) return false;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) {
            throw new IllegalArgumentException(key + "_must_be_a_boolean");
        }
        return object.get(key).getAsBoolean();
    }

    private void serverStarted(ServerStartedEvent event) {
        if (event.getServer().isDedicatedServer()) return;
        serverSnapshot = null;
        session.set(new WorldSession(event.getServer(), UUID.randomUUID().toString()));
    }

    private void serverStopping(ServerStoppingEvent event) {
        var current = session.get();
        if (current != null && current.server() == event.getServer()) {
            session.compareAndSet(current, null);
            if (game != null) game.stopped(event.getServer());
            if (agents != null) agents.worldClosed(current.id());
            serverSnapshot = null;
        }
    }

    private void serverTick(ServerTickEvent.Post event) {
        if (game != null && !event.getServer().isDedicatedServer()) game.tick(event.getServer(), agents == null ? null : agents::retainedBodies,
            agents == null ? Map::of : agents::agentStates,
            (id, body) -> { if (agents != null) agents.bodyObserved(id, body, currentSession()); });
        if (DevelopmentWorld.ENABLED) DevelopmentChecks.tick(event.getServer());
        var current = session.get();
        if (current == null || current.server() != event.getServer() || event.getServer().getTickCount() % 5 != 0) return;
        var player = localPlayer == null ? null : event.getServer().getPlayerList().getPlayer(localPlayer);
        if (player == null) return;
        var result = new JsonObject();
        result.addProperty("session", current.id());
        result.addProperty("sampledAtMs", System.currentTimeMillis());
        result.addProperty("tick", event.getServer().getTickCount());
        result.addProperty("dimension", player.level().dimension().location().toString());
        result.add("player", Observations.entity(player));
        var nearby = player.serverLevel().getEntities(player, player.getBoundingBox().inflate(32), Entity::isAlive);
        nearby.sort(Comparator.comparingDouble(player::distanceToSqr));
        var entities = new JsonArray();
        nearby.stream().limit(64).forEach(entity -> entities.add(Observations.entity(entity)));
        result.add("entities", entities);
        result.addProperty("entitiesTruncated", nearby.size() > 64);
        result.addProperty("radius", 32);
        serverSnapshot = result;
    }

    private void clientTick(ClientTickEvent.Post event) {
        DevelopmentWorld.tick();
        var mc = Minecraft.getInstance();
        paused = mc.isPaused();
        if (game != null) game.clientTick();
        localPlayer = mc.player == null ? null : mc.player.getUUID();
        if (++clientTicks % 5 != 0) return;
        var result = new JsonObject();
        var current = session.get();
        result.addProperty("protocol", 1);
        result.addProperty("sampledAtMs", System.currentTimeMillis());
        result.addProperty("state", mc.level == null ? "menu" : current == null ? "multiplayer_unsupported" : "world");
        result.addProperty("paused", paused);
        result.addProperty("screen", mc.screen == null ? "game" : mc.screen.getClass().getSimpleName());
        if (current != null && mc.level != null) {
            result.addProperty("session", current.id());
            var authoritative = serverSnapshot;
            if (authoritative != null && authoritative.get("session").getAsString().equals(current.id())) {
                result.add("server", authoritative);
            }
            var target = new JsonObject();
            if (mc.hitResult instanceof EntityHitResult hit) {
                target.addProperty("kind", "entity");
                target.addProperty("uuid", hit.getEntity().getUUID().toString());
            } else if (mc.hitResult instanceof BlockHitResult hit && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                var pos = hit.getBlockPos();
                target.addProperty("kind", "block");
                target.add("position", Observations.coordinates(pos.getX(), pos.getY(), pos.getZ()));
                target.addProperty("face", hit.getDirection().getName());
                target.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
            } else {
                target.addProperty("kind", "miss");
            }
            result.add("crosshair", target);
        }
        var messages = new JsonArray();
        synchronized (chat) { chat.forEach(messages::add); }
        result.add("recentChat", messages);
        snapshot = JSON.toJson(result);
    }

    private void chatReceived(ClientChatReceivedEvent event) {
        synchronized (chat) {
            chat.addLast(event.getMessage().getString());
            while (chat.size() > 20) chat.removeFirst();
        }
    }

    private LocalBridge.Reply command(JsonObject request) {
        var current = session.get();
        if (current == null || !current.id().equals(request.get("session").getAsString())) {
            return LocalBridge.error(409, "world_changed");
        }
        if (paused) return LocalBridge.error(409, "game_paused");
        String text = request.get("command").getAsString().strip();
        if (text.isEmpty() || text.length() > 4096 || text.contains("\n") || text.contains("\r")) {
            return LocalBridge.error(400, "invalid_command");
        }
        var reply = new CompletableFuture<LocalBridge.Reply>();
        var state = new AtomicReference<>("queued");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        current.server().execute(() -> {
            if (System.nanoTime() > deadline || !state.compareAndSet("queued", "running")) {
                reply.complete(LocalBridge.error(408, "expired_before_execution"));
                return;
            }
            if (session.get() != current || paused) {
                reply.complete(LocalBridge.error(409, "world_changed_or_paused"));
                return;
            }
            var player = localPlayer == null ? null : current.server().getPlayerList().getPlayer(localPlayer);
            if (player == null) {
                reply.complete(LocalBridge.error(409, "player_unavailable"));
                return;
            }
            try {
                // Preserve the player's own permission level; never elevate to server console.
                current.server().getCommands().performPrefixedCommand(player.createCommandSourceStack(), text);
                reply.complete(new LocalBridge.Reply(200, "{\"status\":\"dispatched\"}"));
            } catch (RuntimeException exception) {
                LOG.error("Too Many Agents command failed", exception);
                reply.complete(LocalBridge.error(500, "command_failed"));
            }
        });
        try {
            return reply.get(4, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            return state.compareAndSet("queued", "cancelled") ? LocalBridge.error(408, "expired_before_execution") :
                LocalBridge.error(504, "outcome_unknown_do_not_retry");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state.compareAndSet("queued", "cancelled");
            return LocalBridge.error(503, "interrupted");
        } catch (java.util.concurrent.ExecutionException exception) {
            return LocalBridge.error(500, "command_failed");
        }
    }
}
