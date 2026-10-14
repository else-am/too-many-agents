package toomanyagents;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** A small client cache for requested POVs. Server data crosses threads only as serialized packets and NBT. */
public final class RemoteTerrain {
    private static final int RADIUS = 64, MAX_BYTES = 32 * 1024 * 1024, MAX_ENTITIES = 256;
    private static final Map<Long, LevelChunk> chunks = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> hashes = new HashMap<>();
    private static final Map<BlockPos, RemoteSection> meshes = new HashMap<>();
    private static final Map<BlockPos, Set<BlockEntity>> globals = new ConcurrentHashMap<>();
    private static final Map<UUID, Entity> entities = new HashMap<>();
    private static final ThreadLocal<Boolean> vanillaOnly = ThreadLocal.withInitial(() -> false);
    private static volatile ClientLevel level;
    private static SectionRenderDispatcher dispatcher;
    private static long lastUse;
    private static boolean drawing;
    private static Upload upload;
    private static CompletableFuture<Void> preparing;
    private record ChunkData(ChunkPos pos, byte[] bytes) {}
    private record Payload(List<ChunkData> chunks, List<CompoundTag> entities) {}
    private static final class Upload {
        final Payload data;
        final BooleanSupplier valid;
        final CompletableFuture<Void> result;
        int next;
        Upload(Payload data, BooleanSupplier valid, CompletableFuture<Void> result) {
            this.data = data; this.valid = valid; this.result = result;
        }
    }

    private RemoteTerrain() {}

    /** Called by the client cache only when vanilla has no chunk at these coordinates. */
    public static LevelChunk chunk(ClientLevel source, int x, int z) {
        return source == level && !vanillaOnly.get() ? chunks.get(ChunkPos.asLong(x, z)) : null;
    }

    private static LevelChunk vanilla(int x, int z) {
        vanillaOnly.set(true);
        try { return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false); }
        finally { vanillaOnly.set(false); }
    }

    public static Iterable<Entity> visibleEntities(ClientLevel source, Iterable<Entity> original) {
        if (!drawing || source != level) return original;
        var result = new ArrayList<Entity>();
        var seen = new HashSet<UUID>();
        original.forEach(entity -> { result.add(entity); seen.add(entity.getUUID()); });
        entities.forEach((id, entity) -> { if (!seen.contains(id)) result.add(entity); });
        return result;
    }

    static Entity entity(UUID id) { return entities.get(id); }
    static boolean remoteOnly(BlockPos origin) { return level != null && vanilla(origin.getX() >> 4, origin.getZ() >> 4) == null; }
    static void drawing(boolean value) { drawing = value; }
    static Set<BlockEntity> globalEntities() {
        var result = new HashSet<BlockEntity>();
        globals.values().forEach(result::addAll);
        return result;
    }

    static com.google.gson.JsonObject snapshot() {
        var result = new com.google.gson.JsonObject();
        result.addProperty("chunks", chunks.size());
        result.addProperty("meshes", meshes.size());
        result.addProperty("entities", entities.size());
        result.addProperty("preparing", preparing != null && !preparing.isDone());
        return result;
    }

    static CompletableFuture<Void> prepare(UUID body, BooleanSupplier valid) {
        var result = new CompletableFuture<Void>();
        var mc = Minecraft.getInstance();
        mc.execute(() -> {
            var server = mc.getSingleplayerServer();
            if (server == null || mc.level == null || !valid.getAsBoolean()) {
                result.completeExceptionally(new IllegalStateException("world_session_changed")); return;
            }
            if (level != mc.level || dispatcher != mc.levelRenderer.sectionRenderDispatcher) clear();
            level = mc.level;
            dispatcher = mc.levelRenderer.sectionRenderDispatcher;
            preparing = result;
            lastUse = System.nanoTime();
            String dimension = level.dimension().location().toString();
            server.execute(() -> {
                try {
                    if (!valid.getAsBoolean()) throw new IllegalStateException("world_session_changed");
                    ServerLevel world = null;
                    Entity found = null;
                    for (var candidate : server.getAllLevels()) {
                        var entity = candidate.getEntity(body);
                        if (entity != null) { world = candidate; found = entity; break; }
                    }
                    if (world == null || found == null) throw new IllegalStateException("body_unloaded");
                    if (!world.dimension().location().toString().equals(dimension))
                        throw new IllegalStateException("POV in another dimension is not available yet.");
                    var origin = found.blockPosition();
                    var source = world;
                    RemoteChunks.get(server).prepare(source, origin, RADIUS, valid).whenComplete((ready, failure) -> {
                        if (failure != null) { result.completeExceptionally(failure); return; }
                        mc.execute(() -> {
                            if (!valid.getAsBoolean() || level != mc.level) {
                                result.completeExceptionally(new IllegalStateException("world_session_changed")); return;
                            }
                            var missing = new ArrayList<ChunkPos>();
                            var needed = new HashSet<Long>();
                            int x0 = (origin.getX() - RADIUS) >> 4, x1 = (origin.getX() + RADIUS) >> 4;
                            int z0 = (origin.getZ() - RADIUS) >> 4, z1 = (origin.getZ() + RADIUS) >> 4;
                            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                                if (vanilla(x, z) == null) { missing.add(new ChunkPos(x, z)); needed.add(ChunkPos.asLong(x, z)); }
                            }
                            prune(needed);
                            server.execute(() -> collect(server, source, body, origin, missing, valid, result));
                        });
                    });
                } catch (Exception failure) { result.completeExceptionally(failure); }
            });
        });
        return result;
    }

    private static void collect(MinecraftServer server, ServerLevel source, UUID body, BlockPos origin, List<ChunkPos> missing,
                                BooleanSupplier valid, CompletableFuture<Void> result) {
        try {
            if (!server.isRunning() || !valid.getAsBoolean()) throw new IllegalStateException("world_session_changed");
            var actor = source.getEntity(body);
            if (actor == null || actor.blockPosition().distSqr(origin) > 64)
                throw new IllegalStateException("The agent moved while its view was being prepared. Request another view.");
            var packets = new ArrayList<ChunkData>();
            int size = 0;
            for (var pos : missing) {
                var chunk = source.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) throw new IllegalStateException("remote_terrain_unloaded");
                var packet = new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null);
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), source.registryAccess());
                try {
                    ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                    size += buffer.readableBytes();
                    if (size > MAX_BYTES) throw new IllegalStateException("remote_view_memory_limit");
                    var bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); packets.add(new ChunkData(pos, bytes));
                } finally { buffer.release(); }
            }
            var records = new ArrayList<CompoundTag>();
            var nearby = source.getEntities((Entity) null, actor.getBoundingBox().inflate(64), Entity::isAlive);
            if (nearby.size() > MAX_ENTITIES) throw new IllegalStateException("remote_view_entity_limit");
            for (var entity : nearby) {
                var tag = new CompoundTag();
                if (entity.save(tag)) records.add(tag);
            }
            var data = new Payload(packets, records);
            Minecraft.getInstance().execute(() -> {
                if (!valid.getAsBoolean()) { result.completeExceptionally(new IllegalStateException("world_session_changed")); return; }
                upload = new Upload(data, valid, result);
                lastUse = System.nanoTime();
            });
        } catch (Exception failure) { result.completeExceptionally(failure); }
    }

    /** At most two packet uploads per rendered frame; idle caches expire without a background renderer. */
    static void tick() {
        var mc = Minecraft.getInstance();
        if (level != null && (mc.level != level || dispatcher != mc.levelRenderer.sectionRenderDispatcher
            || (preparing == null || preparing.isDone()) && upload == null
                && System.nanoTime() - lastUse > 10_000_000_000L)) { clear(); return; }
        var work = upload;
        if (work == null) return;
        try {
            if (!work.valid.getAsBoolean() || mc.level != level) throw new IllegalStateException("world_session_changed");
            for (int i = 0; i < 2 && work.next < work.data.chunks.size(); i++) apply(work.data.chunks.get(work.next++));
            if (work.next < work.data.chunks.size()) return;
            level.getLightEngine().runLightUpdates();
            entities.clear();
            for (var record : work.data.entities) {
                EntityType.loadEntityRecursive(record, level, loaded -> {
                    if (entities.size() >= MAX_ENTITIES) throw new IllegalStateException("remote_view_entity_limit");
                    loaded.setOldPosAndRot();
                    entities.put(loaded.getUUID(), loaded);
                    return loaded;
                });
            }
            upload = null;
            work.result.complete(null);
        } catch (Exception failure) { upload = null; work.result.completeExceptionally(failure); }
    }

    private static void apply(ChunkData data) {
        var pos = data.pos;
        if (vanilla(pos.x, pos.z) != null) return;
        int hash = Arrays.hashCode(data.bytes);
        if (hashes.getOrDefault(pos.toLong(), ~hash) == hash) return;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data.bytes), level.registryAccess());
        try {
            var packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            var chunk = new LevelChunk(level, pos);
            var chunkData = packet.getChunkData();
            var blocks = chunkData.getReadBuffer();
            try { chunk.replaceWithPacketData(blocks, chunkData.getHeightmaps(), chunkData.getBlockEntitiesTagsConsumer(pos.x, pos.z)); }
            finally { blocks.release(); }
            chunks.put(pos.toLong(), chunk);
            hashes.put(pos.toLong(), hash);
            var lighting = level.getLightEngine();
            for (int i = 0; i < chunk.getSectionsCount(); i++)
                lighting.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), chunk.getSection(i).hasOnlyAir());
            var light = packet.getLightData();
            var sky = light.getSkyUpdates().iterator();
            var block = light.getBlockUpdates().iterator();
            for (int i = 0; i < lighting.getLightSectionCount(); i++) {
                var section = SectionPos.of(pos, lighting.getMinLightSection() + i);
                if (light.getSkyYMask().get(i) || light.getEmptySkyYMask().get(i))
                    lighting.queueSectionData(LightLayer.SKY, section, light.getSkyYMask().get(i) ? new DataLayer(sky.next()) : new DataLayer());
                if (light.getBlockYMask().get(i) || light.getEmptyBlockYMask().get(i))
                    lighting.queueSectionData(LightLayer.BLOCK, section, light.getBlockYMask().get(i) ? new DataLayer(block.next()) : new DataLayer());
            }
            lighting.setLightEnabled(pos, true);
            meshes.forEach((origin, mesh) -> {
                if (Math.abs((origin.getX() >> 4) - pos.x) <= 1 && Math.abs((origin.getZ() >> 4) - pos.z) <= 1) mesh.setDirty(false);
            });
        } finally { buffer.release(); }
    }

    static SectionRenderDispatcher.RenderSection mesh(BlockPos origin) {
        lastUse = System.nanoTime();
        if (meshes.size() >= 1024 && !meshes.containsKey(origin)) throw new IllegalStateException("remote_mesh_limit");
        return meshes.computeIfAbsent(origin.immutable(), key -> new RemoteSection(dispatcher, key));
    }

    /** Only private indices change; the player's shared meshes and dispatcher camera stay untouched. */
    static void sortTransparency(List<SectionRenderDispatcher.RenderSection> sections, Vec3 eye) {
        for (var section : sections) if (section instanceof RemoteSection remote) remote.sortTransparency(eye);
    }

    private static final class RemoteSection extends SectionRenderDispatcher.RenderSection {
        private boolean released;
        private SectionRenderDispatcher.CompiledSection sortedSection;
        private Vec3 sortedEye;

        RemoteSection(SectionRenderDispatcher owner, BlockPos origin) {
            owner.super(-1, origin.getX(), origin.getY(), origin.getZ());
        }

        @Override public synchronized void setCompiled(SectionRenderDispatcher.CompiledSection value) {
            if (!released) compiled.set(value);
        }

        @Override public synchronized void updateGlobalBlockEntities(Collection<BlockEntity> values) {
            if (!released) globals.put(getOrigin().immutable(), Set.copyOf(values));
        }

        void sortTransparency(Vec3 eye) {
            var section = getCompiled();
            if (section.transparencyState == null || section.isEmpty(RenderType.translucent())
                || section == sortedSection && eye.equals(sortedEye)) return;
            var origin = getOrigin();
            var sorting = VertexSorting.byDistance((float) (eye.x - origin.getX()),
                (float) (eye.y - origin.getY()), (float) (eye.z - origin.getZ()));
            try (var builder = new ByteBufferBuilder(1024)) {
                var indices = section.transparencyState.buildSortedIndexBuffer(builder, sorting);
                if (indices != null) {
                    var buffer = getBuffer(RenderType.translucent());
                    buffer.bind();
                    try { buffer.uploadIndexBuffer(indices); }
                    finally { VertexBuffer.unbind(); }
                }
            }
            sortedSection = section;
            sortedEye = eye;
        }

        @Override public void releaseBuffers() {
            // Compiler callbacks can arrive after cancellation; invalidate before freeing anything.
            synchronized (this) { released = true; globals.remove(getOrigin()); }
            sortedSection = null;
            sortedEye = null;
            super.releaseBuffers();
        }
    }

    private static void prune(Set<Long> keep) {
        for (long key : Set.copyOf(chunks.keySet())) {
            if (keep.contains(key)) continue;
            var pos = new ChunkPos(key);
            chunks.remove(key); hashes.remove(key);
            if (vanilla(pos.x, pos.z) == null) {
                var lighting = level.getLightEngine();
                lighting.setLightEnabled(pos, false);
                for (int y = lighting.getMinLightSection(); y < lighting.getMaxLightSection(); y++) {
                    var section = SectionPos.of(pos, y);
                    lighting.queueSectionData(LightLayer.BLOCK, section, null);
                    lighting.queueSectionData(LightLayer.SKY, section, null);
                }
                for (int y = level.getMinSection(); y < level.getMaxSection(); y++) lighting.updateSectionStatus(SectionPos.of(pos, y), true);
            }
        }
        meshes.entrySet().removeIf(entry -> {
            if (keep.contains(ChunkPos.asLong(entry.getKey()))) return false;
            entry.getValue().releaseBuffers(); globals.remove(entry.getKey()); return true;
        });
    }

    private static void clear() {
        if (preparing != null) preparing.completeExceptionally(new IllegalStateException("remote_view_released"));
        preparing = null;
        if (upload != null) upload.result.completeExceptionally(new IllegalStateException("remote_view_released"));
        upload = null;
        if (level != null) prune(Set.of());
        meshes.values().forEach(SectionRenderDispatcher.RenderSection::releaseBuffers);
        meshes.clear(); globals.clear(); chunks.clear(); hashes.clear(); entities.clear();
        level = null; dispatcher = null; drawing = false;
    }
}
