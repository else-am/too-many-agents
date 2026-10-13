package toomanyagents.stations;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A station: a block an agent works at. Pistons can't move it and explosions can't break it. An occupied station
 * can't be broken at all (see Stations); a free one drops an item carrying its settings, in creative too.
 */
public final class StationBlock extends BaseEntityBlock {
    private static final MapCodec<StationBlock> CODEC = simpleCodec(StationBlock::new);

    StationBlock(Properties properties) {
        super(properties);
    }

    @Override protected MapCodec<StationBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new StationBlockEntity(pos, state); }

    static boolean occupied(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StationBlockEntity block && block.station().occupied();
    }

    /** No cracks on an occupied station. */
    @Override protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return occupied(level, pos) ? 0 : super.getDestroyProgress(state, player, level, pos);
    }

    /** The block takes its id and name now, rather than when it first ticks. */
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StationBlockEntity block)
            StationIndex.get(level.getServer()).added(block);
    }

    // Like a shulker box: creative breaking would otherwise drop nothing and lose the settings.
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
            && level.getBlockEntity(pos) instanceof StationBlockEntity block) {
            var stack = new ItemStack(this);
            stack.applyComponents(block.collectComponents());
            var item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            item.setDefaultPickUpDelay();
            level.addFreshEntity(item);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** However the block goes, including commands, the index forgets it. Unloading isn't removal. */
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.isClientSide && !state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof StationBlockEntity block)
            StationIndex.get(level.getServer()).removed(block);
        super.onRemove(state, level, pos, replacement, moved);
    }
}
