package toomanyagents.screens;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A placed screen: a thin panel hanging on a wall, like a painting. pos is its bottom-left cell as seen from the
 * front, and it extends right and up from there. Its record reaches the client as ordinary synced entity data.
 */
public final class ScreenEntity extends HangingEntity {
    public static final int MAX_SIZE = 16;
    static final double DEPTH = 1 / 16.0;

    private static final EntityDataAccessor<String> ID = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> URL = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> OPENED_BY = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> WIDTH = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HEIGHT = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.INT);
    /** The name of whoever holds the screen's lease, for its bar. Never saved: leases live in memory. */
    private static final EntityDataAccessor<String> HOLDER = SynchedEntityData.defineId(ScreenEntity.class, EntityDataSerializers.STRING);

    public ScreenEntity(EntityType<? extends ScreenEntity> type, Level level) {
        super(type, level);
    }

    ScreenEntity(Level level, BlockPos pos, Direction facing, int width, int height, Screen record) {
        super(Screens.ENTITY.get(), level, pos);
        entityData.set(WIDTH, width);
        entityData.set(HEIGHT, height);
        setRecord(record);
        setDirection(facing);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ID, "").define(NAME, "").define(URL, "").define(OPENED_BY, "").define(WIDTH, 1).define(HEIGHT, 1).define(HOLDER, "");
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (WIDTH.equals(key) || HEIGHT.equals(key)) recalculateBoundingBox();
    }

    public Screen record() { return new Screen(entityData.get(ID), entityData.get(NAME), entityData.get(URL), entityData.get(OPENED_BY)); }
    public int width() { return entityData.get(WIDTH); }
    public int height() { return entityData.get(HEIGHT); }
    String holder() { return entityData.get(HOLDER); }
    void setHolder(String holder) { entityData.set(HOLDER, holder); }
    public Direction facing() { return direction; }
    /** The viewer's right when looking at the front of a screen facing this way. */
    static Direction right(Direction facing) { return facing.getCounterClockWise(); }

    /** Server only: changes the record and keeps the world's index in step. */
    void setRecord(Screen record) {
        boolean closed = !entityData.get(URL).isEmpty() && record.url().isEmpty();
        entityData.set(ID, record.id());
        entityData.set(NAME, record.name());
        entityData.set(URL, record.url());
        entityData.set(OPENED_BY, record.openedBy());
        if (level() instanceof ServerLevel server && isAddedToLevel()) {
            var index = ScreenIndex.get(server.getServer());
            index.update(this);
            // Closing the page, by a player or an agent, ends its lease.
            if (closed) index.release(server.getServer(), record.id());
        }
    }

    @Override public void onAddedToLevel() {
        super.onAddedToLevel();
        // Gives a new screen its id, and a copied one a fresh id if its own is already taken.
        if (level() instanceof ServerLevel server) ScreenIndex.get(server.getServer()).added(this);
    }

    @Override public void remove(RemovalReason reason) {
        super.remove(reason);
        // Unloading keeps a screen listed; only breaking or discarding it takes it out.
        if (level() instanceof ServerLevel server && reason.shouldDestroy()) ScreenIndex.get(server.getServer()).removed(this);
    }

    @Override protected AABB calculateBoundingBox(BlockPos pos, Direction facing) {
        var far = pos.relative(right(facing), width() - 1).above(height() - 1);
        var cells = AABB.encapsulatingFullBlocks(pos, far);
        // Only the thin slab against the wall.
        Vec3 center = cells.getCenter().relative(facing, -0.5 + DEPTH / 2);
        var axis = facing.getAxis();
        return AABB.ofSize(center, axis == Direction.Axis.X ? DEPTH : cells.getXsize(), cells.getYsize(),
            axis == Direction.Axis.Z ? DEPTH : cells.getZsize());
    }

    /** Players never break a screen by hitting it: bare hands use the page, and its menu picks it up. */
    @Override public boolean skipAttackInteraction(Entity attacker) {
        return attacker instanceof Player || super.skipAttackInteraction(attacker);
    }

    /** Nor by anything they shoot or throw. */
    @Override public boolean hurt(DamageSource source, float amount) {
        return !(source.getEntity() instanceof Player) && super.hurt(source, amount);
    }

    /**
     * Server: the screen is taken down and pops off the wall as an item, saving its name and page, or blank when
     * reset. Removing it closes its page, fails waiting calls and ends its lease.
     */
    void pickUp(boolean reset) {
        var stack = reset ? new ItemStack(Screens.ITEM.get()) : ScreenItem.stack(record());
        var out = Vec3.atLowerCornerOf(direction.getNormal());
        var at = getBoundingBox().getCenter().add(out.scale(0.3));
        var item = new ItemEntity(level(), at.x, at.y, at.z, stack, out.x * 0.12, 0.1, out.z * 0.12);
        item.setDefaultPickUpDelay();
        playSound(SoundEvents.COPPER_BREAK, 1, 1);
        discard();
        level().addFreshEntity(item);
    }

    @Override public void dropItem(@Nullable Entity breaker) {
        if (!level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) return;
        playSound(SoundEvents.COPPER_BREAK, 1, 1);
        if (breaker instanceof Player player && player.hasInfiniteMaterials()) return;
        spawnAtLocation(ScreenItem.stack(record()));
    }

    @Override public void playPlacementSound() { playSound(SoundEvents.COPPER_PLACE, 1, 1); }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        Screen.CODEC.encodeStart(NbtOps.INSTANCE, record()).ifSuccess(value -> tag.put("screen", value));
        tag.putByte("facing", (byte) direction.get2DDataValue());
        tag.putInt("width", width());
        tag.putInt("height", height());
        super.addAdditionalSaveData(tag);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        Screen.CODEC.parse(NbtOps.INSTANCE, tag.get("screen")).ifSuccess(this::setRecord);
        entityData.set(WIDTH, Math.clamp(tag.getInt("width"), 1, MAX_SIZE));
        entityData.set(HEIGHT, Math.clamp(tag.getInt("height"), 1, MAX_SIZE));
        direction = Direction.from2DDataValue(tag.getByte("facing"));
        super.readAdditionalSaveData(tag);
        setDirection(direction);
    }

    // Like a painting, a screen never moves once placed.
    @Override public void moveTo(double x, double y, double z, float yRot, float xRot) { setPos(x, y, z); }
    @Override public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) { setPos(x, y, z); }
    @Override public Vec3 trackingPosition() { return Vec3.atLowerCornerOf(pos); }

    @Override public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        return new ClientboundAddEntityPacket(this, direction.get3DDataValue(), getPos());
    }

    @Override public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        setDirection(Direction.from3DDataValue(packet.getData()));
    }

    @Override public ItemStack getPickResult() { return new ItemStack(Screens.ITEM.get()); }

    /** Large screens stay drawn from as far as their pages are worth seeing. */
    @Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 128 * 128; }
}
