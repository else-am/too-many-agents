package toomanyagents.stations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import toomanyagents.ProjectColor;

/** A placed project block: one BB project, and its color for the tint. Blank until a project is chosen. */
public final class ProjectBlockEntity extends BlockEntity {
    private String projectId = "", color = "";

    public ProjectBlockEntity(BlockPos pos, BlockState state) {
        super(Stations.PROJECT_ENTITY.get(), pos, state);
    }

    public String projectId() { return projectId; }
    public String color() { return color; }

    /** Server: sets one of BB's projects, never a world project, or clears it with a blank id. The tint is its color. */
    public void setProject(String projectId) {
        var index = StationIndex.get(level.getServer());
        projectId = index.requireProject(projectId.strip());
        set(projectId, index.color(projectId));
    }

    void clear() { set("", ""); }

    /** BB changed this project's color. */
    void recolor(String color) {
        if (!ProjectColor.valid(color) ? !this.color.isEmpty() : !color.equals(this.color)) set(projectId, color);
    }

    private void set(String projectId, String color) {
        this.projectId = projectId;
        this.color = ProjectColor.valid(color) ? color : "";
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) StationIndex.get(server.getServer()).projectBlockLoaded(this);
    }

    @Override public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) StationIndex.get(server.getServer()).projectBlockUnloaded(this);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        projectId = tag.getString("projectId");
        color = tag.getString("color");
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("projectId", projectId);
        tag.putString("color", color);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveCustomOnly(registries); }

    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    /** Redraws the tint when the project changes. */
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        super.onDataPacket(connection, packet, registries);
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_IMMEDIATE);
    }
}
