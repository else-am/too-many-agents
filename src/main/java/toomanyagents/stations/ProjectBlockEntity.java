package toomanyagents.stations;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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

    /** Server: sets the project, or clears it with a blank id. A world project is rejected by the caller. */
    public void setProject(String projectId, String color) {
        projectId = projectId.strip();
        if (projectId.length() > 200 || projectId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("A project id must be at most 200 characters, with no control characters.");
        if (!color.isEmpty() && !ProjectColor.valid(color)) throw new IllegalArgumentException("A project color must look like #RRGGBB.");
        this.projectId = projectId;
        this.color = projectId.isEmpty() ? "" : color;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
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
