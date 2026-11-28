package games.brennan.dungeontrain.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Constellation block, look 2: a see-through dark-blue haze with a few star specks (a translucent
 * model, {@code models/block/constellation_haze.json}); step into it and the sky patch opens round
 * you. No collision, no occlusion; invisible and inert outside a perks-on world.
 */
public class ConstellationHazeBlock extends Block {

    public ConstellationHazeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Not drawn at all where perks are off: the block is not there. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return ConstellationBlocks.active() ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ConstellationBlocks.outline(context);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** A run of haze reads as one volume: no inner faces between two haze blocks, as with glass. */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        return adjacent.is(this) || super.skipRendering(state, adjacent, direction);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
