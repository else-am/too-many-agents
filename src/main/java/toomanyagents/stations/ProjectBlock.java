package toomanyagents.stations;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A marker for a BB project, tinted its color. It confines nothing and nothing reads its position. */
public final class ProjectBlock extends BaseEntityBlock {
    private static final MapCodec<ProjectBlock> CODEC = simpleCodec(ProjectBlock::new);

    ProjectBlock(Properties properties) {
        super(properties);
    }

    @Override protected MapCodec<ProjectBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProjectBlockEntity(pos, state); }
}
