package toomanyagents;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Temporary, shared simulation tickets. All access is on the owning server thread. */
public final class RemoteChunks {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("too_many_agents_remote", Comparator.comparingLong(ChunkPos::toLong));
    private static final Map<MinecraftServer, RemoteChunks> instances = new HashMap<>();
    private static final int MAX_CHUNKS = 1024, STARTS_PER_TICK = 2, MAX_LOADING = 8;
    private static final long GRACE_NANOS = 10_000_000_000L, PREPARE_NANOS = 20_000_000_000L;
    private record Chunk(ServerLevel level, ChunkPos pos) {
        boolean ready() { return level.areEntitiesLoaded(pos.toLong()) && level.isPositionEntityTicking(pos.getWorldPosition()); }
    }
    private record Need(Set<Chunk> chunks, long until) {}
    private record Preparation(String owner, Set<Chunk> chunks, BooleanSupplier valid,
                               long deadline, CompletableFuture<Void> result) {}
    private final Map<String, Need> needs = new LinkedHashMap<>();
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private final ArrayList<Preparation> preparations = new ArrayList<>();

    private RemoteChunks() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> get(event.getServer()).tick());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            var chunks = instances.remove(event.getServer());
            if (chunks != null) chunks.close();
        });
    }

    public static RemoteChunks get(MinecraftServer server) {
        return instances.computeIfAbsent(server, ignored -> new RemoteChunks());
    }

    /** Refresh an area's demand. Minecraft shares actual chunks with player tickets and other owners. */
    public void retain(String owner, ServerLevel level, BlockPos center, int radius) {
        retain(owner, area(level, center, radius));
    }

    private void retain(String owner, Set<Chunk> area) {
        var total = new HashSet<>(area);
        needs.forEach((id, need) -> { if (!id.equals(owner)) total.addAll(need.chunks); });
        if (total.size() > MAX_CHUNKS) {
            var previous = needs.get(owner);
            if (previous != null) needs.put(owner, new Need(previous.chunks, System.nanoTime() + GRACE_NANOS));
            throw new IllegalStateException("remote_chunk_limit: wait for another area to be released");
        }
        needs.put(owner, new Need(area, System.nanoTime() + GRACE_NANOS));
    }

    public void release(String owner) { needs.remove(owner); }

    /** Prepare before a mutation; never replay the mutation or block the server while generating terrain. */
    public CompletableFuture<Void> prepare(ServerLevel level, BlockPos center, int radius, BooleanSupplier valid) {
        return prepare(area(level, center, radius), valid);
    }

    public CompletableFuture<Void> prepareBox(ServerLevel level, BlockPos min, BlockPos max, BooleanSupplier valid) {
        int x0 = min.getX() >> 4, x1 = max.getX() >> 4, z0 = min.getZ() >> 4, z1 = max.getZ() >> 4;
        if (x0 > x1 || z0 > z1 || (long) (x1 - x0 + 1) * (z1 - z0 + 1) > MAX_CHUNKS
            || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max))
            throw new IllegalArgumentException("remote_area_limit_or_outside_world");
        var area = new LinkedHashSet<Chunk>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) area.add(new Chunk(level, new ChunkPos(x, z)));
        return prepare(area, valid);
    }

    private CompletableFuture<Void> prepare(Set<Chunk> area, BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return CompletableFuture.failedFuture(new IllegalStateException("world_request_cancelled"));
        String owner = "prepare:" + UUID.randomUUID();
        retain(owner, area);
        if (area.stream().allMatch(Chunk::ready)) return CompletableFuture.completedFuture(null);
        var result = new CompletableFuture<Void>();
        preparations.add(new Preparation(owner, area, valid, System.nanoTime() + PREPARE_NANOS, result));
        return result;
    }

    private static Set<Chunk> area(ServerLevel level, BlockPos center, int radius) {
        if (level == null || radius < 0 || radius > 128 || !level.getWorldBorder().isWithinBounds(center))
            throw new IllegalArgumentException("invalid_remote_area");
        var result = new LinkedHashSet<Chunk>();
        int x0 = Math.floorDiv(center.getX() - radius, 16), x1 = Math.floorDiv(center.getX() + radius, 16);
        int z0 = Math.floorDiv(center.getZ() - radius, 16), z1 = Math.floorDiv(center.getZ() + radius, 16);
        // Load the center first, then work outward.
        var middle = new ChunkPos(center);
        for (int ring = 0; ring <= (radius + 15) / 16 + 1; ring++)
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++)
                if (Math.max(Math.abs(x - middle.x), Math.abs(z - middle.z)) == ring)
                    result.add(new Chunk(level, new ChunkPos(x, z)));
        return result;
    }

    private void tick() {
        long now = System.nanoTime();
        // Complete outside iteration: callbacks may request another area.
        var finished = new ArrayList<Runnable>();
        preparations.removeIf(request -> {
            String failure = request.result.isCancelled() || !request.valid.getAsBoolean() ? "world_request_cancelled"
                : now >= request.deadline ? "remote_terrain_timeout" : null;
            if (failure != null) {
                needs.remove(request.owner);
                finished.add(() -> request.result.completeExceptionally(new IllegalStateException(failure)));
                return true;
            }
            needs.put(request.owner, new Need(request.chunks, now + GRACE_NANOS));
            if (!request.chunks.stream().allMatch(Chunk::ready)) return false;
            finished.add(() -> request.result.complete(null));
            return true;
        });
        needs.values().removeIf(need -> now >= need.until);
        var wanted = new LinkedHashSet<Chunk>();
        needs.values().forEach(need -> wanted.addAll(need.chunks));
        tickets.removeIf(chunk -> {
            if (wanted.contains(chunk)) return false;
            chunk.level.getChunkSource().removeRegionTicket(TICKET, chunk.pos, 2, chunk.pos, true);
            return true;
        });
        int loading = (int) tickets.stream().filter(chunk -> !chunk.ready()).count(), started = 0;
        for (var chunk : wanted) {
            if (tickets.contains(chunk)) continue;
            if (!chunk.ready() && (loading >= MAX_LOADING || started >= STARTS_PER_TICK)) continue;
            chunk.level.getChunkSource().addRegionTicket(TICKET, chunk.pos, 2, chunk.pos, true);
            tickets.add(chunk);
            if (!chunk.ready()) { loading++; started++; }
        }
        // A dimension with no human players must not put its ticketed entities to sleep after 300 ticks.
        tickets.stream().map(Chunk::level).distinct().forEach(ServerLevel::resetEmptyTime);
        finished.forEach(Runnable::run);
    }

    public JsonObject snapshot() {
        var result = new JsonObject();
        result.addProperty("owners", needs.size());
        result.addProperty("ticketedChunks", tickets.size());
        result.addProperty("loadingChunks", tickets.stream().filter(chunk -> !chunk.ready()).count());
        result.addProperty("preparing", preparations.size());
        result.addProperty("chunkLimit", MAX_CHUNKS);
        return result;
    }

    private void close() {
        tickets.forEach(chunk -> chunk.level.getChunkSource().removeRegionTicket(TICKET, chunk.pos, 2, chunk.pos, true));
        tickets.clear();
        needs.clear();
        var waiting = new ArrayList<>(preparations);
        preparations.clear();
        waiting.forEach(request -> request.result.completeExceptionally(new IllegalStateException("world_session_changed")));
    }
}
