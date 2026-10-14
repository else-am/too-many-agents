package toomanyagents.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import toomanyagents.stations.StationIndex;

/**
 * The agents' screen tools. A call is resolved, checked and leased on the server thread, from the world's screen
 * and station indexes; carried out on the client, where pages live; and settled on a thread of its
 * own, so whoever waits on it never runs on either game thread. Nothing here retries.
 */
public final class ScreenTools {
    /** The longest any call takes, below the bridge's own wait. */
    public static final long CALL_SECONDS = 40;
    private static final long CLIENT_START_NANOS = 2_000_000_000L, OPEN_SECONDS = 15, CDP_SECONDS = 30;
    private static final int CDP_LIMIT = 64 * 1024, PNG_LIMIT = 5 * 1024 * 1024, LIST_DEFAULT = 20, LIST_LIMIT = 50;

    /** Where calls settle. */
    private static final Executor SETTLE = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "too-many-agents-screens");
        thread.setDaemon(true);
        return thread;
    });

    /** The calling agent, and every agent's thread for naming who sits at each station. body is null when absent. */
    public record Caller(String agentId, String threadId, String name, String projectId, UUID body, Map<String, String> threads) {}

    /** A failure the agent reads as a result, with any details it needs to choose again. */
    static final class ScreenError extends RuntimeException {
        final String code;
        final JsonObject details;

        ScreenError(String code, String message) { this(code, message, new JsonObject()); }

        ScreenError(String code, String message, JsonObject details) {
            super(message, null, false, false);
            this.code = code;
            this.details = details;
        }
    }

    /** A call resolved on the server, ready to carry out. */
    public static final class Call {
        private final Supplier<CompletableFuture<JsonObject>> work;
        private final Runnable end;
        /** What the call waits for on the page; let go when it ends, so a page that never answers keeps nothing. */
        private final Set<CompletableFuture<?>> waiters;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Call(Supplier<CompletableFuture<JsonObject>> work, Runnable end, Set<CompletableFuture<?>> waiters) {
            this.work = work;
            this.end = end;
            this.waiters = waiters;
        }

        public CompletableFuture<JsonObject> run() { return work.get(); }

        /** Ends the call's waits and its part in its lease, once, however the call ended. Any thread. */
        public void finish() {
            if (!finished.compareAndSet(false, true)) return;
            var ended = new ScreenError("cancelled", "The call ended.");
            for (var waiter : waiters) waiter.completeExceptionally(ended);
            end.run();
        }
    }

    private ScreenTools() {}

    public static void activeThreads(MinecraftServer server, Set<String> threads) { ScreenIndex.get(server).active(threads); }

    /**
     * Server thread: resolves the call against the screen and station indexes. player is the local player;
     * live says whether the call may still go ahead, checked again at each later step.
     */
    public static Call begin(MinecraftServer server, UUID player, Caller caller, String tool, JsonObject args, BooleanSupplier live) {
        var index = ScreenIndex.get(server);
        var layout = new Layout(server, player, caller, index);
        Set<CompletableFuture<?>> waiters = ConcurrentHashMap.newKeySet();
        if (tool.equals("screen_list")) return new Call(list(layout, limit(args), live), () -> {}, waiters);
        var row = layout.choose(args.get("screen"));
        var entry = row.entry;
        String id = entry.screen().id();
        Supplier<CompletableFuture<JsonObject>> work = switch (tool) {
            case "screen_open" -> open(server, entry, caller, args, live, waiters);
            case "screen_cdp" -> cdp(entry, args, live, waiters);
            case "screen_close" -> () -> onClient(live, () -> {
                var view = Screens.existing(entry.entity());
                if (view != null) {
                    ScreenInput.closing(view);
                    view.shutDown();
                }
                return null;
            }).thenCompose(ignored -> onServer(server, live, () -> {
                // Idle in the world record too, and free for anyone, before the call reports success.
                edit(server, entry, record -> new Screen(record.id(), record.name(), "", ""));
                index.release(server, id);
                index.releaseUse(server, id);
                return result(object("screen", id));
            }));
            case "screen_screenshot" -> () -> onPage(entry, live, waiters, view -> {
                return wait(waiters, view.nextFrame(), 5, "The screen did not paint a fresh frame.")
                    .thenCompose(ignored -> onClient(live, () -> screenshot(entry, view))).thenCompose(shot -> shot);
            });
            default -> throw new ScreenError("invalid_request", "Unknown screen tool " + tool + ".");
        };
        if (tool.equals("screen_screenshot")) {
            var use = index.use(server, id, caller.threadId());
            return new Call(work, () -> {
                if (server.isRunning()) server.execute(() -> index.endedUse(server, id, caller.threadId(), use));
            }, waiters);
        }

        // Page changes need a lease, but no simulated terrain: the saved index owns the page.
        var lease = index.lease(id);
        if (lease != null && !lease.thread.equals(caller.threadId())) {
            var leased = new JsonObject();
            leased.addProperty("thread", lease.thread);
            leased.addProperty("secondsLeft", lease.secondsLeft());
            leased.addProperty("url", entry.screen().url());
            throw new ScreenError("leased", "Another thread is using screen " + id + ". Wait for its lease to end, or use another screen.",
                object("leased", leased));
        }
        var taken = index.take(server, id, caller.threadId(), caller.name());
        var use = index.use(server, id, caller.threadId());
        return new Call(work, () -> {
            if (server.isRunning()) server.execute(() -> {
                index.ended(server, id, taken);
                index.endedUse(server, id, caller.threadId(), use);
            });
        }, waiters);
    }

    /** Turns a finished call into the tool's result, on the settling thread. A ScreenError becomes an error result. */
    public static CompletableFuture<JsonObject> settle(CompletableFuture<JsonObject> call) {
        return call.handleAsync((value, failure) -> {
            if (failure == null) return value;
            var cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ScreenError error) return error(error);
            if (cause instanceof TimeoutException) return error(new ScreenError("timeout",
                "The call didn't finish in " + CALL_SECONDS + "s. The outcome is unknown; check the screen before acting again."));
            if (cause instanceof IllegalStateException && cause.getMessage() != null) {
                var lifecycle = lifecycle(cause.getMessage());
                if (lifecycle != null) return error(lifecycle);
            }
            throw cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
        }, SETTLE);
    }

    /** The game's own reasons a call stopped, as screen errors. Anything else stays a failure. */
    private static ScreenError lifecycle(String reason) {
        var details = object("reason", reason);
        return switch (reason) {
            case "expired_before_execution" -> new ScreenError("timeout",
                "Minecraft didn't get to the call in time. Part of it may have run; check the screen before acting again.", details);
            case "world_session_changed", "world_closed", "minecraft_closed", "bb_changed", "bb_disconnected" ->
                new ScreenError("unavailable", "Minecraft isn't running this world right now.", details);
            case "conversation_unavailable", "thread_not_active", "thread_archived", "agent_archived", "agent_restored", "agent_removed", "interrupted" ->
                new ScreenError("unavailable", "This thread is no longer an active agent conversation.", details);
            case "tool_cancelled", "turn_closed", "body_lost", "body_removed", "body_changed", "body_dimension_changed", "body_reloaded", "body_unloaded" ->
                new ScreenError("unavailable", "The call was cancelled. Part of it may have run; check the screen before acting again.", details);
            default -> null;
        };
    }

    /** Server thread: ends a thread's leases early, or everyone's when thread is null. */
    public static void release(MinecraftServer server, String thread) {
        ScreenIndex.get(server).releaseThread(server, thread);
    }

    // The tools.

    /** Server thread: describes the screens now; the client only adds what it alone knows. */
    private static Supplier<CompletableFuture<JsonObject>> list(Layout layout, int limit, BooleanSupplier live) {
        var rows = layout.rows;
        var shown = rows.subList(0, Math.min(rows.size(), limit)).stream().map(row -> new Listed(row.entry, layout.json(row))).toList();
        var you = layout.you();
        int truncated = rows.size() - shown.size();
        // Whether the user's client can show each page, and its title, are known only there.
        return () -> onClient(live, () -> {
            boolean trusted = Trust.allowedHere();
            var screens = new JsonArray();
            for (var row : shown) {
                var json = row.json.deepCopy();
                var view = Screens.existing(row.entry.entity());
                json.addProperty("title", view != null && !view.url.isBlank() ? view.title : "");
                json.addProperty("available", trusted);
                json.addProperty("awake", view != null && view.browser != null);
                screens.add(json);
            }
            var result = new JsonObject();
            result.add("you", you);
            result.add("screens", screens);
            result.addProperty("truncated", truncated);
            return result(result);
        });
    }

    private record Listed(ScreenIndex.Entry entry, JsonObject json) {}

    private static CompletableFuture<JsonObject> onPage(ScreenIndex.Entry entry, BooleanSupplier live,
            Set<CompletableFuture<?>> waiters, java.util.function.Function<ScreenView, CompletableFuture<JsonObject>> work) {
        return onClient(live, () -> {
            var view = view(entry);
            return wait(waiters, view.readyPage(), OPEN_SECONDS, "The screen did not wake in time.")
                .thenCompose(ignored -> onClient(live, () -> work.apply(view))).thenCompose(answer -> answer);
        }).thenCompose(answer -> answer);
    }

    private static Supplier<CompletableFuture<JsonObject>> open(MinecraftServer server, ScreenIndex.Entry entry, Caller caller, JsonObject args,
                                                                BooleanSupplier live, Set<CompletableFuture<?>> waiters) {
        String kind = string(args, "kind"), target = string(args, "target");
        if (kind == null || target == null) throw invalid("screen_open needs kind and target.");
        String baseOverride = args.has("base") ? string(args, "base") : null;
        if (args.has("base") && (!kind.equals("changes") || baseOverride == null || baseOverride.isBlank() || baseOverride.length() > 1024
            || baseOverride.chars().anyMatch(Character::isISOControl)))
            throw invalid("base must be a nonblank Git ref of at most 1024 characters, for kind changes.");
        String url = switch (kind) {
            case "url" -> webAddress(target.strip());
            case "file", "changes" -> fileAddress(target, kind.equals("changes"), baseOverride);
            default -> throw invalid("kind must be url, file or changes.");
        };
        String destination = url;
        String name = args.has("name") ? name(args) : null;
        String id = entry.screen().id(), thread = caller.threadId();
        if (name != null) ScreenIndex.get(server).requireName(name, id);
        return () -> onServer(server, live, () -> {
            // Reserve the name before navigating, so a conflicting rename cannot change the page.
            if (name != null) edit(server, entry, record -> new Screen(record.id(), name, record.url(), record.openedBy()));
            return null;
        }).thenCompose(ignored -> onClient(live, () -> {
            var view = view(entry);
            if (Chromium.phase() == Chromium.Phase.FAILED) throw new ScreenError("unavailable", "The browser failed to start.");
            var loaded = wait(waiters, view.open(destination, thread), OPEN_SECONDS,
                "The page didn't finish loading in " + OPEN_SECONDS + "s. It may still load; check the screen before acting again.");
            return new Opened(view, view.generation, loaded);
        })).thenCompose(opened -> onServer(server, live, () -> {
            edit(server, entry, record -> new Screen(record.id(), record.name(), destination, thread));
            return opened;
        })).thenCompose(opened -> opened.loaded.thenCompose(ignored -> onClient(live, () -> result(page(opened.view, opened.generation, id)))));
    }

    private record Opened(ScreenView view, long generation, CompletableFuture<Void> loaded) {}

    private static Supplier<CompletableFuture<JsonObject>> cdp(ScreenIndex.Entry entry, JsonObject args, BooleanSupplier live, Set<CompletableFuture<?>> waiters) {
        String method = string(args, "method");
        if (method == null || method.isBlank()) throw invalid("screen_cdp needs a method, e.g. Runtime.evaluate.");
        var params = args.get("params");
        if (params != null && !params.isJsonNull() && !params.isJsonObject()) throw invalid("params must be an object.");
        String parameters = params == null || params.isJsonNull() ? "{}" : params.toString();
        String id = entry.screen().id();
        return () -> onPage(entry, live, waiters, view -> {
            long generation = view.generation;
            return wait(waiters, view.devTools(method, parameters), CDP_SECONDS,
                    "DevTools didn't answer in " + CDP_SECONDS + "s. The outcome is unknown; check the page before acting again.")
                .handle((text, failure) -> {
                    if (failure != null) {
                        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                        if (cause instanceof ScreenError error) throw error;
                        throw new ScreenError("cdp_error", String.valueOf(cause.getMessage()));
                    }
                    if (text.getBytes(StandardCharsets.UTF_8).length > CDP_LIMIT)
                        throw new ScreenError("too_large", "The result is over " + CDP_LIMIT / 1024 + " KB."
                            + (method.equals("Page.captureScreenshot") ? " Use screen_screenshot for images." : " Ask for less, e.g. return only the fields you need."));
                    return text.isEmpty() ? (JsonElement) new JsonObject() : JsonParser.parseString(text);
                })
                .thenCompose(value -> onClient(live, () -> {
                    var result = page(view, generation, id);
                    result.add("result", value);
                    return result(result);
                }));
        });
    }

    /** The frame already painted for the page, at its exact size. */
    private static CompletableFuture<JsonObject> screenshot(ScreenIndex.Entry entry, ScreenView view) {
        String id = entry.screen().id();
        if (view.browser == null) throw idle(id);
        if (!view.painted()) throw new ScreenError("unavailable", "Screen " + id + " hasn't painted its page yet.");
        var image = view.frame();
        var metadata = page(view, view.generation, id);
        metadata.addProperty("capturedAtMs", System.currentTimeMillis());
        return CompletableFuture.supplyAsync(() -> {
            byte[] png;
            try (image) { png = image.asByteArray(); } catch (IOException e) { throw new UncheckedIOException(e); }
            if (png.length > PNG_LIMIT) throw new ScreenError("too_large", "The screenshot is over " + PNG_LIMIT / (1024 * 1024) + " MB as a PNG.");
            var content = new JsonArray();
            content.add(object("type", "text", "text", metadata.toString()));
            var picture = object("type", "image", "mimeType", "image/png");
            picture.addProperty("data", Base64.getEncoder().encodeToString(png));
            content.add(picture);
            var result = new JsonObject();
            result.add("content", content);
            return result;
        }, SETTLE);
    }

    // Where screens are, and who may use them. Server thread.

    private static final class Row {
        final ScreenIndex.Entry entry;
        /** The station the screen is linked to, or null. */
        final StationIndex.Entry station;
        final Double user, you;

        Row(ScreenIndex.Entry entry, StationIndex.Entry station, Double user, Double you) {
            this.entry = entry;
            this.station = station;
            this.user = user;
            this.you = you;
        }

        /** The nearer of the user and the caller, or null when both are elsewhere. */
        Double nearest() { return user == null ? you : you == null ? user : Math.min(user, you); }
    }

    private static final class Layout {
        final Caller caller;
        final ScreenIndex index;
        /** The caller's station, or null. */
        final StationIndex.Entry station;
        /** Screens the caller may use: its station's first, then unlinked ones nearest the user or the caller. */
        final List<Row> rows = new ArrayList<>();
        /** Screens linked to another station. Never listed; naming one fails with no_access. */
        final List<Row> foreign = new ArrayList<>();

        Layout(MinecraftServer server, UUID playerId, Caller caller, ScreenIndex index) {
            this.caller = caller;
            this.index = index;
            var stations = StationIndex.get(server).entries();
            station = caller.agentId().isBlank() ? null : stations.stream().filter(entry -> entry.station().agentId().equals(caller.agentId())).findFirst().orElse(null);
            var player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            Entity body = null;
            if (caller.body() != null) for (var level : server.getAllLevels()) if ((body = level.getEntity(caller.body())) != null) break;
            for (var entry : index.entries()) {
                var center = panel(entry).getCenter();
                var row = new Row(entry, linked(entry, stations), distance(player, entry, center), distance(body, entry, center));
                // A linked screen is its station's occupant's alone; a free station's screens are no one's.
                if (row.station == null || row.station == station) rows.add(row);
                else foreign.add(row);
            }
            rows.sort(Comparator.<Row>comparingInt(row -> row.station != null ? 0 : 1)
                .thenComparingDouble(row -> row.nearest() == null ? Double.MAX_VALUE : row.nearest())
                .thenComparing(row -> row.entry.screen().name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.entry.screen().id()));
        }

        /** The screen a call names, or the caller's only station screen when it names none. */
        Row choose(JsonElement argument) {
            if (argument == null || argument.isJsonNull()) {
                if (station == null)
                    throw choose("You have no station, so pass `screen`: a UUID or unique name. screen_list shows the screens you can use.", List.of());
                var mine = rows.stream().filter(row -> row.station != null).toList();
                if (mine.size() == 1) return mine.getFirst();
                throw choose(mine.isEmpty() ? "Your station has no screen. Pass `screen`; screen_list shows the screens you can use."
                    : "Your station has several screens. Pass `screen` to choose one.", mine);
            }
            if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()) throw invalid("screen must be an id or a name.");
            String wanted = argument.getAsString().strip();
            boolean uuid = wanted.matches("(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
            String id = uuid ? UUID.fromString(wanted).toString() : null;
            java.util.function.Predicate<Row> named = row -> uuid ? row.entry.screen().id().equals(id) : row.entry.screen().name().equalsIgnoreCase(wanted);
            for (var row : rows) if (named.test(row)) return row;
            for (var row : foreign) {
                if (!named.test(row)) continue;
                var owner = row.station.station();
                throw new ScreenError("no_access", "Screen " + wanted + " is linked to station " + owner.name()
                    + (owner.occupied() ? ", which belongs to another agent." : ", which has no agent yet, so no agent can use it."),
                    object("station", station(row.station, true)));
            }
            throw new ScreenError("not_found", uuid ? "There is no screen " + wanted + "." : "No screen is called \"" + wanted + "\".");
        }

        private ScreenError choose(String message, List<Row> candidates) {
            var list = new JsonArray();
            candidates.stream().limit(LIST_LIMIT).forEach(row -> list.add(json(row)));
            return new ScreenError("choose_screen", message, object("candidates", list));
        }

        JsonObject you() {
            var you = new JsonObject();
            you.add("station", station == null ? JsonNull.INSTANCE : station(station, false));
            you.addProperty("project", caller.projectId());
            return you;
        }

        /** A screen as agents see it. title and available are filled in on the client. */
        JsonObject json(Row row) {
            var record = row.entry.screen();
            var json = object("id", record.id(), "name", record.name(), "url", record.url(), "title", "");
            var viewport = new JsonArray();
            for (int pixels : ScreenView.viewport(row.entry.width(), row.entry.height())) viewport.add(pixels);
            json.add("viewport", viewport);
            json.add("station", row.station == null ? JsonNull.INSTANCE : station(row.station, false));
            json.addProperty("yours", row.station != null);
            json.addProperty("openedByYou", !record.openedBy().isBlank() && record.openedBy().equals(caller.threadId()));
            json.addProperty("available", false);
            var lease = index.lease(record.id());
            if (lease == null) json.add("lease", JsonNull.INSTANCE);
            else {
                var held = new JsonObject();
                held.addProperty("thread", lease.thread);
                held.addProperty("secondsLeft", lease.secondsLeft());
                json.add("lease", held);
            }
            var distance = new JsonObject();
            distance.add("user", row.user == null ? JsonNull.INSTANCE : new JsonPrimitive(Math.round(row.user)));
            distance.add("you", row.you == null ? JsonNull.INSTANCE : new JsonPrimitive(Math.round(row.you)));
            json.add("distance", distance);
            return json;
        }

        /** A station by id and name; with its occupant's thread when naming someone else's. */
        private JsonObject station(StationIndex.Entry entry, boolean occupant) {
            var json = object("id", entry.station().id(), "name", entry.station().name());
            if (occupant) {
                String thread = caller.threads().get(entry.station().agentId());
                json.add("thread", thread == null || thread.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(thread));
            }
            return json;
        }
    }

    /** A screen linked to a station. */
    public record Link(String screenId, String screenName, String stationId) {}

    /** Server thread: every linked screen, by the same rule the tools use. Unlinked screens are left out. */
    public static List<Link> links(MinecraftServer server) {
        var stations = StationIndex.get(server).entries();
        var links = new ArrayList<Link>();
        for (var entry : ScreenIndex.get(server).entries()) {
            var station = linked(entry, stations);
            if (station != null) links.add(new Link(entry.screen().id(), entry.screen().name(), station.station().id()));
        }
        return links;
    }

    /** Server thread: archiving closes only built-in pages linked to this station, including unloaded panels. */
    public static void closeStationBuiltins(MinecraftServer server, String stationId) {
        var index = ScreenIndex.get(server);
        var stations = StationIndex.get(server).entries();
        for (var entry : List.copyOf(index.entries())) {
            if (!Chromium.builtin(entry.screen().url())) continue;
            var station = linked(entry, stations);
            if (station == null || !station.station().id().equals(stationId)) continue;
            index.edit(server, entry.screen().id(), record -> new Screen(record.id(), record.name(), "", ""));
            index.release(server, entry.screen().id());
            index.releaseUse(server, entry.screen().id());
        }
    }

    /** The station whose area holds any part of the panel, nearest its center, ties going to the lower id. */
    private static StationIndex.Entry linked(ScreenIndex.Entry entry, Collection<StationIndex.Entry> stations) {
        var panel = panel(entry);
        var center = panel.getCenter();
        StationIndex.Entry linked = null;
        double nearest = Double.MAX_VALUE;
        for (var candidate : stations) {
            if (!candidate.dimension().equals(entry.dimension())) continue;
            int r = candidate.station().radius();
            var pos = candidate.pos();
            var area = AABB.encapsulatingFullBlocks(pos.offset(-r, 0, -r), pos.offset(r, candidate.station().height() - 1, r));
            if (!area.intersects(panel)) continue;
            double d = Vec3.atCenterOf(pos).distanceTo(center);
            if (d < nearest || d == nearest && candidate.station().id().compareTo(linked.station().id()) < 0) {
                nearest = d;
                linked = candidate;
            }
        }
        return linked;
    }

    /** The screen's panel: the thin slab against its support, as ScreenEntity's bounding box. */
    private static AABB panel(ScreenIndex.Entry entry) {
        var facing = Direction.byName(entry.facing());
        if (facing == null) facing = Direction.NORTH;
        var up = Direction.byName(entry.up());
        if (up == null || up.getAxis() == facing.getAxis()) up = facing.getAxis().isHorizontal() ? Direction.UP : Direction.NORTH;
        return ScreenEntity.bounds(entry.pos(), facing, up, entry.width(), entry.height());
    }

    /** How far an entity is from a screen, or null when it's absent or in another dimension. */
    private static Double distance(Entity entity, ScreenIndex.Entry entry, Vec3 center) {
        if (entity == null || !entity.level().dimension().location().toString().equals(entry.dimension())) return null;
        return entity.position().distanceTo(center);
    }

    // On the client. Render thread.

    /** Runs a step on the client if it starts soon and the call is still live. */
    private static <T> CompletableFuture<T> onClient(BooleanSupplier live, Supplier<T> work) {
        var result = new CompletableFuture<T>();
        long deadline = System.nanoTime() + CLIENT_START_NANOS;
        Minecraft.getInstance().execute(() -> step(result, deadline, live, work));
        return result;
    }

    /** Runs a step on the server, on the same terms. The server keeps taking these while the game is paused. */
    private static <T> CompletableFuture<T> onServer(MinecraftServer server, BooleanSupplier live, Supplier<T> work) {
        var result = new CompletableFuture<T>();
        long deadline = System.nanoTime() + CLIENT_START_NANOS;
        try {
            server.execute(() -> step(result, deadline, live, work));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static <T> void step(CompletableFuture<T> result, long deadline, BooleanSupplier live, Supplier<T> work) {
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("expired_before_execution");
            if (!live.getAsBoolean()) throw new IllegalStateException("world_session_changed");
            result.complete(work.get());
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    /** Server thread: changes a placed screen's record, which then reaches every client. */
    private static void edit(MinecraftServer server, ScreenIndex.Entry entry, java.util.function.UnaryOperator<Screen> change) {
        ScreenIndex.get(server).edit(server, entry.screen().id(), change);
    }

    /** The screen's view on the user's client, which must be able to show pages. */
    private static ScreenView view(ScreenIndex.Entry entry) {
        var mc = Minecraft.getInstance();
        String id = entry.screen().id();
        if (mc.level == null || mc.getSingleplayerServer() == null) throw notLoaded(id);
        if (!Trust.allowedHere()) throw new ScreenError("unavailable", "The user hasn't allowed pages in this world yet.");
        return Screens.view(entry);
    }

    /** The page a call acted on, unless another page has replaced it since. */
    private static JsonObject page(ScreenView view, long generation, String id) {
        if (view.generation != generation) throw new ScreenError("unavailable", "Screen " + id + "'s page changed before the call finished.");
        return object("screen", id, "url", view.url, "title", view.title);
    }

    /** Gives up waiting on the page after this long. The page's own work isn't undone, only no longer awaited. */
    private static <T> CompletableFuture<T> wait(Set<CompletableFuture<?>> waiters, CompletableFuture<T> waiter, long seconds, String message) {
        waiters.add(waiter);
        CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS).execute(() -> waiter.completeExceptionally(new ScreenError("timeout", message)));
        return waiter;
    }

    // Arguments.

    private static String webAddress(String target) {
        try {
            var uri = new URI(target);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null || target.equals("about:blank")) return target;
        } catch (Exception ignored) {}
        throw invalid("target must be an http or https URL. Use kind file for a file.");
    }

    private static String fileAddress(String target, boolean changes, String baseOverride) {
        // Only explicit URLs carry query/fragment syntax. Raw filenames keep literal ? and #.
        boolean location = target.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && !target.matches("(?i)^[a-z]:[\\\\/].*");
        Path path;
        var params = new java.util.LinkedHashMap<String, String>();
        String fragment = "";
        try {
            if (location) {
                var uri = new URI(target);
                if (!"tma".equalsIgnoreCase(uri.getScheme()) || !"files".equalsIgnoreCase(uri.getRawAuthority())
                    || uri.getPath() == null || !uri.getPath().startsWith("/") || uri.getPath().startsWith("//"))
                    throw invalid("Use a tma://files location with an absolute root, without a username or port.");
                path = FilePages.path(target);
                var query = FilePages.query(uri);
                if (!Set.of("open", "mode", "base").containsAll(query.keySet()))
                    throw invalid("Files locations accept only open, mode and base; agents cannot open the picker.");
                String open = query.get("open");
                if (open != null && !open.isEmpty()) {
                    var selected = Path.of(open);
                    if (open.length() > 4096 || selected.isAbsolute() || open.startsWith("\\") || open.matches("(?i)^[a-z]:.*")
                        || open.chars().anyMatch(Character::isISOControl)) throw invalid("open must be a relative file path inside the root.");
                    for (var part : selected) if (part.toString().equals("..")) throw invalid("open must stay inside the root.");
                    params.put("open", selected.normalize().toString().replace(java.io.File.separatorChar, '/'));
                }
                String mode = query.get("mode");
                if (mode != null) {
                    if (!Set.of("files", "changes").contains(mode)) throw invalid("mode must be files or changes.");
                    params.put("mode", mode);
                }
                String base = query.get("base");
                if (base != null) {
                    if (base.isBlank() || base.length() > 1024 || base.chars().anyMatch(Character::isISOControl))
                        throw invalid("base must be a nonblank Git ref of at most 1024 characters.");
                    params.put("base", base);
                }
                if (uri.getFragment() != null && !uri.getFragment().isEmpty()) {
                    var lines = java.util.regex.Pattern.compile("L([0-9]{1,7})(?:-L?([0-9]{1,7}))?").matcher(uri.getFragment());
                    if (!lines.matches()) throw invalid("Use a line fragment such as #L20 or #L20-L40.");
                    int start = Integer.parseInt(lines.group(1));
                    int end = lines.group(2) == null ? start : Integer.parseInt(lines.group(2));
                    if (start < 1 || end < start || end > 2_097_153) throw invalid("The line range is outside the preview limit.");
                    fragment = "#L" + start + (end == start ? "" : "-L" + end);
                }
            } else path = Path.of(target);
        } catch (ScreenError error) { throw error; }
        catch (Exception error) { throw invalid("target isn't a valid file path or tma://files location."); }
        // Checked as text only: the server thread doesn't wait on the filesystem or grant access.
        if (!path.isAbsolute()) throw invalid("Use an absolute file path.");
        String address = Builtin.files(path.normalize());
        String root = URI.create(address).getPath();
        if (root.equals("/_app") || root.startsWith("/_app/") || root.equals("/_api") || root.startsWith("/_api/"))
            throw invalid("Internal files resources are not screen locations.");
        if (changes) params.put("mode", "changes");
        if (baseOverride != null) params.put("base", baseOverride);
        var query = new ArrayList<String>();
        params.forEach((key, value) -> query.add(key + "=" + FilePages.encode(value)));
        return address + (query.isEmpty() ? "" : "?" + String.join("&", query)) + fragment;
    }

    /** DevTools domains that act within the page. The rest reach the browser, other pages or the computer's files. */
    private static final Set<String> CDP_DOMAINS = Set.of("Accessibility", "Animation", "Audits", "CSS", "CacheStorage", "Console", "DOM",
        "DOMDebugger", "DOMSnapshot", "DOMStorage", "Debugger", "DeviceOrientation", "Emulation", "EventBreakpoints", "Fetch", "HeapProfiler",
        "IO", "IndexedDB", "Input", "Inspector", "LayerTree", "Log", "Media", "Network", "Overlay", "Page", "Performance",
        "PerformanceTimeline", "Profiler", "Runtime", "Schema", "Storage", "WebAudio");
    /** Methods in those domains that read or write local files, or close the browser out from under the screen. */
    private static final Set<String> CDP_REFUSED = Set.of("DOM.setFileInputFiles", "Page.handleFileChooser", "Page.setDownloadBehavior",
        "Page.close", "Network.loadNetworkResource");

    /** Checked on the client after view_only, so a built-in page always answers view_only. */
    static void refuseUnsafe(String method, JsonElement params) {
        int dot = method.indexOf('.');
        boolean files = method.equals("Input.dispatchDragEvent") && params != null && params.isJsonObject()
            && params.getAsJsonObject().get("data") instanceof JsonObject data && data.get("files") instanceof JsonArray list && !list.isEmpty();
        if (dot < 0 || !CDP_DOMAINS.contains(method.substring(0, dot)) || CDP_REFUSED.contains(method) || files)
            throw new ScreenError("refused", method + " isn't available on screens: it reaches beyond the page.");
    }

    /** Explicit names must be nonblank. Uniqueness is checked on the server before changing the page. */
    private static String name(JsonObject args) {
        String name = string(args, "name");
        if (name == null) throw invalid("name must be text.");
        return ScreenIndex.validName(name);
    }

    private static int limit(JsonObject args) {
        var value = args.get("limit");
        if (value == null || value.isJsonNull()) return LIST_DEFAULT;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || value.getAsDouble() != Math.rint(value.getAsDouble())
            || value.getAsDouble() < 1 || value.getAsDouble() > LIST_LIMIT)
            throw invalid("limit must be a whole number from 1 to " + LIST_LIMIT + ".");
        return value.getAsInt();
    }

    private static String string(JsonObject args, String key) {
        var value = args.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    // Results.

    private static JsonObject result(JsonObject value) {
        var content = new JsonArray();
        content.add(object("type", "text", "text", value.toString()));
        var result = new JsonObject();
        result.add("content", content);
        return result;
    }

    private static JsonObject error(ScreenError error) {
        var body = object("code", error.code, "message", error.getMessage());
        error.details.entrySet().forEach(entry -> body.add(entry.getKey(), entry.getValue()));
        var content = new JsonArray();
        content.add(object("type", "text", "text", object("error", body).toString()));
        var result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", true);
        return result;
    }

    private static ScreenError invalid(String message) { return new ScreenError("invalid_request", message); }
    private static ScreenError idle(String id) { return new ScreenError("unavailable", "Screen " + id + " is idle. Open a page on it first."); }
    private static ScreenError notLoaded(String id) {
        return new ScreenError("unavailable", "Screen " + id + " isn't loaded on the user's client: it's out of range, or in another dimension.");
    }

    private static JsonObject object(Object... pairs) {
        var json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            var value = pairs[i + 1];
            if (value instanceof JsonElement element) json.add((String) pairs[i], element);
            else json.addProperty((String) pairs[i], (String) value);
        }
        return json;
    }
}
