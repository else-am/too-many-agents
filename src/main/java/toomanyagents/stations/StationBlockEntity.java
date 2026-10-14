package toomanyagents.stations;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A placed station's record. Its settings travel with the item; id and occupant stay with the placed block. */
public final class StationBlockEntity extends BlockEntity {
    private Station station = Station.BLANK;

    public StationBlockEntity(BlockPos pos, BlockState state) {
        super(Stations.STATION_ENTITY.get(), pos, state);
    }

    public Station station() { return station; }

    /**
     * Server: applies a configuration request. Names must be unique, and a project must be one of BB's, never a
     * world project. Area and behaviors apply at once; the project can't change while occupied.
     */
    public Station configure(JsonObject request) {
        var updated = station.configure(request);
        if (!updated.projectId().equals(station.projectId())) index().requireProject(updated.projectId());
        if (request.has("name")) updated = updated.withName(index().requireName(updated.name(), station.id()));
        if (!updated.equals(station)) {
            adopt(updated);
            index().update(this);
        }
        return station;
    }

    /** Takes a record without consulting the index. */
    void adopt(Station station) {
        this.station = station;
        // A resident block can be outside the loaded-ticket range during a canceled unload.
        // Its record must still change, but neighbor notifications must not load that chunk.
        if (level != null && level.isLoaded(worldPosition)) {
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    String dimension() { return level.dimension().location().toString(); }
    MinecraftServer server() { return level.getServer(); }
    private StationIndex index() { return StationIndex.get(server()); }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) index().added(this);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        station = Station.CODEC.parse(NbtOps.INSTANCE, tag.get("station")).result().orElse(Station.BLANK);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        Station.CODEC.encodeStart(NbtOps.INSTANCE, station).ifSuccess(value -> tag.put("station", value));
    }

    // A placed item brings its settings; the id and occupant come from the index once the block joins the level.
    @Override protected void applyImplicitComponents(DataComponentInput input) {
        var settings = input.get(Stations.STATION_DATA.get());
        if (settings != null) station = settings.withIdentity(station.id(), station.agentId());
    }

    @Override protected void collectImplicitComponents(DataComponentMap.Builder components) {
        components.set(Stations.STATION_DATA.get(), station.settings());
    }

    @Override public void removeComponentsFromTag(CompoundTag tag) { tag.remove("station"); }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveCustomOnly(registries); }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
