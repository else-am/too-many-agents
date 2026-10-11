package toomanyagents.screens;

import com.mojang.serialization.MapCodec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * A screen block faces the player who placed it. A complete flat rectangle of same-facing blocks forms one screen;
 * any other shape stays dark until it is completed.
 */
public final class ScreenBlock extends Block implements EntityBlock {
    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final int MAX_SIZE = 16;

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<ScreenBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ScreenBlockEntity(pos, state); }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        // Building a screen yourself is consent to load pages in this world.
        if (level instanceof ServerLevel server && placer instanceof Player) Trust.allow(server.getServer());
    }

    // Shapes are re-checked a tick later, once the block entity exists and a whole /fill has landed.
    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        if (!level.isClientSide && !old.is(this)) level.scheduleTick(pos, this, 1);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!level.isClientSide && !next.is(this)) {
            Direction right = right(state.getValue(FACING));
            for (var side : new BlockPos[] {pos.relative(right), pos.relative(right.getOpposite()), pos.above(), pos.below()})
                if (level.getBlockState(side).is(this)) level.scheduleTick(side, this, 1);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) { reform(level, pos); }

    /** The viewer's right when looking at the front of a screen facing this way. */
    public static Direction right(Direction facing) { return facing.getCounterClockWise(); }

    /** The blocks connected to start in its plane that face the same way. */
    static List<BlockPos> members(Level level, BlockPos start) {
        var state = level.getBlockState(start);
        Direction facing = state.getValue(FACING), right = right(facing);
        var found = new ArrayList<BlockPos>();
        var seen = new HashSet<BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        queue.add(start);
        seen.add(start);
        // Anything larger than the biggest screen can never form, so the search stops there.
        int limit = (MAX_SIZE + 1) * (MAX_SIZE + 1);
        while (!queue.isEmpty() && found.size() < limit) {
            var pos = queue.poll();
            found.add(pos);
            for (var next : new BlockPos[] {pos.relative(right), pos.relative(right.getOpposite()), pos.above(), pos.below()}) {
                if (seen.add(next) && level.getBlockState(next).is(state.getBlock()) && level.getBlockState(next).getValue(FACING) == facing) queue.add(next);
            }
        }
        return found;
    }

    /** Forms or dissolves the screen containing pos, keeping the newest page among its blocks. */
    static void reform(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScreenBlock)) return;
        Direction right = right(state.getValue(FACING));
        var members = members(level, pos);
        int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        ScreenBlockEntity newest = null;
        for (var member : members) {
            int r = along(member.subtract(pos), right);
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minY = Math.min(minY, member.getY()); maxY = Math.max(maxY, member.getY());
            if (level.getBlockEntity(member) instanceof ScreenBlockEntity entity && (newest == null || entity.stamp > newest.stamp)) newest = entity;
        }
        int width = maxR - minR + 1, height = maxY - minY + 1;
        boolean formed = members.size() == width * height && width <= MAX_SIZE && height <= MAX_SIZE;
        var anchor = pos.relative(right, minR).atY(minY);
        for (var member : members) {
            if (!(level.getBlockEntity(member) instanceof ScreenBlockEntity entity)) continue;
            int w = formed && member.equals(anchor) ? width : 0, h = formed && member.equals(anchor) ? height : 0;
            // Only a complete screen shares one page; a broken one keeps each block's page for when it is rebuilt.
            boolean adopt = formed && newest != null && entity.stamp != newest.stamp;
            if (entity.width == w && entity.height == h && !adopt) continue;
            if (adopt) { entity.url = newest.url; entity.stamp = newest.stamp; }
            entity.width = w; entity.height = h;
            entity.setChanged();
            level.sendBlockUpdated(member, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Sets the page of the complete screen anchored at anchor. */
    static void setUrl(Level level, BlockPos anchor, String url) {
        if (!(level.getBlockEntity(anchor) instanceof ScreenBlockEntity screen) || !screen.isAnchor() || screen.url.equals(url)) return;
        Direction right = right(screen.getBlockState().getValue(FACING));
        long stamp = level.getGameTime();
        for (int r = 0; r < screen.width; r++)
            for (int y = 0; y < screen.height; y++) {
                var member = anchor.relative(right, r).above(y);
                if (!(level.getBlockEntity(member) instanceof ScreenBlockEntity entity)) continue;
                entity.url = url;
                entity.stamp = stamp;
                entity.setChanged();
                level.sendBlockUpdated(member, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_CLIENTS);
            }
    }

    private static int along(BlockPos offset, Direction direction) {
        return offset.getX() * direction.getStepX() + offset.getZ() * direction.getStepZ();
    }
}
