package toomanyagents.screens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block of a screen. Every block keeps the screen's page, so a broken screen gets it back when rebuilt.
 * Only the bottom-left block of a complete screen (its anchor) has a size and draws the page.
 */
public final class ScreenBlockEntity extends BlockEntity {
    String url = "";
    /** Game time the page was last set; the newest page wins when blocks join into one screen. */
    long stamp = -1;
    int width, height;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(Screens.BLOCK_ENTITY.get(), pos, state);
    }

    public boolean isAnchor() { return width > 0; }
    public String url() { return url; }
    public int width() { return width; }
    public int height() { return height; }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("url", url);
        tag.putLong("stamp", stamp);
        tag.putInt("width", width);
        tag.putInt("height", height);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        url = tag.getString("url");
        stamp = tag.contains("stamp") ? tag.getLong("stamp") : -1;
        width = tag.getInt("width");
        height = tag.getInt("height");
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
