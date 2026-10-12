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
import net.minecraft.world.phys.Vec3;

/**
 * The agents' screen tools. A call is resolved, checked and leased on the server thread, from the world's screen
 * index, stations and project boxes; carried out on the client, where pages live; and settled on a thread of its
 * own, so whoever waits on it never runs on either game thread. Nothing here retries.
 */
public final class ScreenTools {
    private static final long CLIENT_START_NANOS = 2_000_000_000L, OPEN_SECONDS = 15, CDP_SECONDS = 30;
    private static final int CDP_LIMIT = 64 * 1024, PNG_LIMIT = 5 * 1024 * 1024, LIST_LIMIT = 50;
    /** A screen belongs to the nearest station within this many blocks. */
    private static final double LINK_DISTANCE = 4;

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

    /**
     * Server thread: resolves the call against the world. world is WorldState's snapshot; player is the local
     * player; live says whether the call may still go ahead, checked again at each later step.
     */
    public static Call begin(MinecraftServer server, JsonObject world, UUID player, Caller caller, String tool, JsonObject args, BooleanSupplier live) {
        var index = ScreenIndex.get(server);
        var layout = new Layout(server, world, player, caller, index);
        Set<CompletableFuture<?>> waiters = ConcurrentHashMap.newKeySet();
        if (tool.equals("screen_list")) {
            boolean mine = args.has("mine") && args.get("mine").isJsonPrimitive() && args.get("mine").getAsBoolean();
            return new Call(list(layout, mine, live), () -> {}, waiters);
        }
        var row = layout.choose(args.get("screen"));
        var entry = row.entry;
        String id = entry.screen().id();
        Supplier<CompletableFuture<JsonObject>> work = switch (tool) {
            case "screen_open" -> open(server, entry, caller, args, live, waiters);
            case "screen_cdp" -> cdp(entry, args, live, waiters);
            case "screen_close" -> () -> onClient(live, () -> {
                var view = view(entry);
                ScreenInput.closing(view);
                view.shutDown();
                return null;
            }).thenCompose(ignored -> onServer(server, live, () -> {
                // Idle in the world record too, and free for anyone, before the call reports success.
                edit(server, entry, record -> new Screen(record.id(), record.name(), "", ""));
                index.release(server, id);
                return result(object("screen", id));
            }));
            case "screen_screenshot" -> () -> onClient(live, () -> screenshot(entry, view(entry))).thenCompose(shot -> shot);
            default -> throw new ScreenError("invalid_request", "Unknown screen tool " + tool + ".");
        };
        if (tool.equals("screen_screenshot")) return new Call(work, () -> {}, waiters);

        // Everything else changes the page, so it needs the screen loaded and its lease.
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.dimension())));
        if (level == null || !(level.getEntity(entry.entity()) instanceof ScreenEntity)) throw notLoaded(id);
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
        return new Call(work, () -> {
            if (server.isRunning()) server.execute(() -> index.ended(server, id, taken));
        }, waiters);
    }

    /** Turns a finished call into the tool's result, on the settling thread. A ScreenError becomes an error result. */
    public static CompletableFuture<JsonObject> settle(CompletableFuture<JsonObject> call) {
        return call.handleAsync((value, failure) -> {
            if (failure == null) return value;
            var cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof ScreenError error) return error(error);
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
    private static Supplier<CompletableFuture<JsonObject>> list(Layout layout, boolean mine, BooleanSupplier live) {
        var rows = layout.rows.stream().filter(row -> !mine || row.station != null && row.station == layout.station).toList();
        var shown = rows.subList(0, Math.min(rows.size(), LIST_LIMIT)).stream().map(row -> new Listed(row.entry, layout.json(row))).toList();
        var you = layout.you();
        int truncated = rows.size() - shown.size();
        // Whether the user's client can show each page, and its title, are known only there.
        return () -> onClient(live, () -> {
            var mc = Minecraft.getInstance();
            boolean trusted = Trust.allowedHere();
            String here = mc.level == null ? "" : mc.level.dimension().location().toString();
            var loaded = new HashSet<UUID>();
            if (mc.level != null) for (var entity : mc.level.entitiesForRendering()) if (entity instanceof ScreenEntity) loaded.add(entity.getUUID());
            var screens = new JsonArray();
            for (var row : shown) {
                var json = row.json.deepCopy();
                var view = Screens.existing(row.entry.entity());
                json.addProperty("title", view != null && !view.url.isBlank() ? view.title : "");
                json.addProperty("available", trusted && row.entry.dimension().equals(here) && loaded.contains(row.entry.entity()));
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

    private static Supplier<CompletableFuture<JsonObject>> open(MinecraftServer server, ScreenIndex.Entry entry, Caller caller, JsonObject args,
                                                                BooleanSupplier live, Set<CompletableFuture<?>> waiters) {
        String kind = string(args, "kind"), target = string(args, "target");
        if (kind == null || target == null) throw invalid("screen_open needs kind and target.");
        String url = switch (kind) {
            case "url" -> webAddress(target.strip());
            case "file" -> fileAddress(target);
            default -> throw invalid("kind must be url or file.");
        };
        String name = args.has("name") ? name(args) : null;
        String id = entry.screen().id(), thread = caller.threadId();
        return () -> onClient(live, () -> {
            var view = view(entry);
            if (Chromium.phase() == Chromium.Phase.FAILED) throw new ScreenError("unavailable", "The browser failed to start.");
            var loaded = wait(waiters, view.open(url), OPEN_SECONDS,
                "The page didn't finish loading in " + OPEN_SECONDS + "s. It may still load; check the screen before acting again.");
            return new Opened(view, view.generation, loaded);
        }).thenCompose(opened -> onServer(server, live, () -> {
            edit(server, entry, record -> new Screen(record.id(), name == null ? record.name() : name, url, thread));
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
        return () -> onClient(live, () -> {
            var view = view(entry);
            if (view.browser == null) throw idle(id);
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
        }).thenCompose(answer -> answer);
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
        /** The caller's project when the screen is in its box; null when shared. */
        final String project;
        /** The station the screen belongs to, or null. */
        final JsonObject station;
        final boolean yours;
        final Double user, you;

        Row(ScreenIndex.Entry entry, String project, JsonObject station, boolean yours, Double user, Double you) {
            this.entry = entry;
            this.project = project;
            this.station = station;
            this.yours = yours;
            this.user = user;
            this.you = you;
        }
    }

    private static final class Layout {
        final Caller caller;
        final ScreenIndex index;
        /** The caller's station, or null. */
        final JsonObject station;
        /** Screens the caller may use, yours first, then nearest the user. */
        final List<Row> rows = new ArrayList<>();
        /** Ids of other projects' screens, which are never described. */
        final HashSet<String> foreign = new HashSet<>();

        Layout(MinecraftServer server, JsonObject world, UUID playerId, Caller caller, ScreenIndex index) {
            this.caller = caller;
            this.index = index;
            var bounds = rows(world, "bounds");
            var stations = rows(world, "stations");
            station = stations.stream().filter(row -> !caller.agentId().isBlank() && caller.agentId().equals(text(row, "agentId"))).findFirst().orElse(null);
            var player = playerId == null ? null : server.getPlayerList().getPlayer(playerId);
            Entity body = null;
            if (caller.body() != null) for (var level : server.getAllLevels()) if ((body = level.getEntity(caller.body())) != null) break;
            for (var entry : index.entries()) {
                var center = center(entry);
                // Any other project's box around a screen makes it theirs, even where boxes overlap.
                var boxes = bounds.stream().filter(box -> text(box, "dimension").equals(entry.dimension()) && distance(box, center) == 0).toList();
                if (boxes.stream().anyMatch(box -> !text(box, "projectId").equals(caller.projectId()))) {
                    foreign.add(entry.screen().id());
                    continue;
                }
                // The nearest station within reach, ties going to the lower id.
                JsonObject linked = null;
                double nearest = Double.MAX_VALUE;
                for (var candidate : stations) {
                    if (!text(candidate, "dimension").equals(entry.dimension())) continue;
                    double d = distance(candidate, center);
                    if (d > LINK_DISTANCE) continue;
                    if (d < nearest || d == nearest && text(candidate, "id").compareTo(text(linked, "id")) < 0) {
                        nearest = d;
                        linked = candidate;
                    }
                }
                rows.add(new Row(entry, boxes.isEmpty() ? null : caller.projectId(), linked, linked != null && linked == station,
                    distance(player, entry, center), distance(body, entry, center)));
            }
            rows.sort(Comparator.<Row>comparingInt(row -> row.yours ? 0 : 1)
                .thenComparingDouble(row -> row.user == null ? Double.MAX_VALUE : row.user)
                .thenComparingLong(row -> number(row.entry.screen().id())));
        }

        /** The screen a call names, or the caller's only station screen when it names none. */
        Row choose(JsonElement argument) {
            if (argument == null || argument.isJsonNull()) {
                if (station == null)
                    throw choose("You have no station, so pass `screen`: an id like s4, or a name. screen_list shows the screens you can use.", List.of());
                var mine = rows.stream().filter(row -> row.station == station).toList();
                if (mine.size() == 1) return mine.getFirst();
                throw choose(mine.isEmpty() ? "Your station has no screen. Pass `screen`; screen_list shows the screens you can use."
                    : "Your station has several screens. Pass `screen` to choose one.", mine);
            }
            if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()) throw invalid("screen must be an id or a name.");
            String wanted = argument.getAsString().strip();
            if (wanted.matches("s\\d+")) {
                for (var row : rows) if (row.entry.screen().id().equals(wanted)) return row;
                if (foreign.contains(wanted)) throw new ScreenError("no_access", "Screen " + wanted + " belongs to another project.");
                throw new ScreenError("not_found", "There is no screen " + wanted + ".");
            }
            var named = rows.stream().filter(row -> row.entry.screen().name().equalsIgnoreCase(wanted)).toList();
            if (named.size() == 1) return named.getFirst();
            if (named.isEmpty()) throw new ScreenError("not_found", "No screen you can use is called \"" + wanted + "\".");
            throw choose("Several screens are called \"" + wanted + "\". Pass one's id.", named);
        }

        private ScreenError choose(String message, List<Row> candidates) {
            var list = new JsonArray();
            candidates.stream().limit(LIST_LIMIT).forEach(row -> list.add(json(row)));
            return new ScreenError("choose_screen", message, object("candidates", list));
        }

        JsonObject you() {
            var you = new JsonObject();
            you.add("station", station == null ? JsonNull.INSTANCE : object("id", text(station, "id"), "label", text(station, "label")));
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
            json.add("project", row.project == null ? JsonNull.INSTANCE : new JsonPrimitive(row.project));
            json.add("station", row.station == null ? JsonNull.INSTANCE : station(row.station));
            json.addProperty("yours", row.yours);
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

        private JsonObject station(JsonObject row) {
            var json = object("id", text(row, "id"), "label", text(row, "label"));
            String thread = caller.threads().get(text(row, "agentId"));
            json.add("thread", thread == null || thread.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(thread));
            return json;
        }
    }

    /** The middle of a screen's panel. */
    private static Vec3 center(ScreenIndex.Entry entry) {
        var facing = Direction.byName(entry.facing());
        var right = ScreenEntity.right(facing == null ? Direction.NORTH : facing);
        return Vec3.atCenterOf(entry.pos()).add(Vec3.atLowerCornerOf(right.getNormal()).scale((entry.width() - 1) / 2.0))
            .add(0, (entry.height() - 1) / 2.0, 0).relative(facing == null ? Direction.NORTH : facing, -0.5 + ScreenEntity.DEPTH / 2);
    }

    /** Distance from a point to a box of whole blocks given by min and max corners; 0 inside it. */
    private static double distance(JsonObject box, Vec3 point) {
        var min = box.getAsJsonArray("min");
        var max = box.getAsJsonArray("max");
        double dx = Math.max(0, Math.max(min.get(0).getAsInt() - point.x, point.x - (max.get(0).getAsInt() + 1)));
        double dy = Math.max(0, Math.max(min.get(1).getAsInt() - point.y, point.y - (max.get(1).getAsInt() + 1)));
        double dz = Math.max(0, Math.max(min.get(2).getAsInt() - point.z, point.z - (max.get(2).getAsInt() + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
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
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.dimension())));
        if (level == null || !(level.getEntity(entry.entity()) instanceof ScreenEntity screen)) throw notLoaded(entry.screen().id());
        var record = screen.record();
        var updated = change.apply(record);
        if (!updated.equals(record)) screen.setRecord(updated);
    }

    /** The screen's view on the user's client, which must be able to show pages. */
    private static ScreenView view(ScreenIndex.Entry entry) {
        var mc = Minecraft.getInstance();
        String id = entry.screen().id();
        if (mc.level == null || !mc.level.dimension().location().toString().equals(entry.dimension())) throw notLoaded(id);
        if (!Trust.allowedHere()) throw new ScreenError("unavailable", "The user hasn't allowed pages in this world yet.");
        for (var entity : mc.level.entitiesForRendering())
            if (entity instanceof ScreenEntity screen && screen.getUUID().equals(entry.entity()) && !screen.isRemoved()) return Screens.view(screen);
        throw notLoaded(id);
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

    private static String fileAddress(String target) {
        Path path;
        try { path = Path.of(target); } catch (Exception e) { throw invalid("target isn't a valid file path."); }
        // Checked as text only: the server thread doesn't wait on the filesystem. A missing file shows Chromium's error page.
        if (!path.isAbsolute()) throw invalid("Use an absolute file path.");
        return path.toUri().toString();
    }

    /** The name a call gives a screen. Empty clears it, as it does for players. */
    private static String name(JsonObject args) {
        String name = string(args, "name");
        if (name == null) throw invalid("name must be text.");
        name = name.strip();
        if (name.codePointCount(0, name.length()) > ScreenInput.MAX_NAME || name.codePoints().anyMatch(Character::isISOControl))
            throw invalid("A screen name has at most " + ScreenInput.MAX_NAME + " characters, with no control characters.");
        return name;
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

    private static List<JsonObject> rows(JsonObject world, String key) {
        var rows = new ArrayList<JsonObject>();
        var array = world.getAsJsonArray(key);
        if (array != null) for (var row : array) rows.add(row.getAsJsonObject());
        return rows;
    }

    private static String text(JsonObject row, String key) {
        var value = row.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long number(String id) {
        try { return Long.parseLong(id.substring(1)); } catch (RuntimeException e) { return Long.MAX_VALUE; }
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
