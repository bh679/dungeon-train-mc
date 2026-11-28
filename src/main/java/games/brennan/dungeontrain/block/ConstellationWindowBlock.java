package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Constellation block, look 1: from outside it is a skybox hole showing the constellation sky
 * ({@link SkyboxSky#CONSTELLATION}); step into it and the sky patch opens round you.
 *
 * <p>A {@link SkyboxBlock} so the existing depth punch and stencil draw its window, but with every
 * solid trait the base class insists on taken back off: no collision (you walk into it, never onto
 * it), no occlusion (the camera sits inside it, and an occluding cube round the camera culls the
 * neighbours it should be showing), and the author's skybox switch does not apply — this block is
 * gated on perks instead ({@link ConstellationBlocks#active()}).</p>
 */
public class ConstellationWindowBlock extends SkyboxBlock {

    public static final MapCodec<ConstellationWindowBlock> CODEC = simpleCodec(ConstellationWindowBlock::new);

    public ConstellationWindowBlock(BlockBehaviour.Properties properties) {
        super(SkyboxSky.CONSTELLATION, properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
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
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }
}
