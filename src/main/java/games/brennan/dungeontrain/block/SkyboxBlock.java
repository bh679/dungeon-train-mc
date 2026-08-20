package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A solid, unmineable full cube that reads as a hole punched through the world:
 * look at it and you see the skybox, even when there is terrain — or a carriage
 * wall — directly behind it.
 *
 * <p>The block itself contributes <em>no</em> chunk geometry
 * ({@link RenderShape#INVISIBLE}); the sky effect is produced entirely on the
 * client by
 * {@link games.brennan.dungeontrain.client.skybox.SkyboxPunchRenderer}, which
 * writes the cube's faces into the depth buffer (colour writes masked off) in the
 * gap between vanilla drawing the sky and drawing terrain. Everything behind the
 * cube then fails the depth test and the already-painted sky survives; everything
 * in front draws normally.</p>
 *
 * <p>Note what is deliberately <b>not</b> set on the block properties in
 * {@link games.brennan.dungeontrain.registry.ModBlocks}: {@code noOcclusion()}.
 * Unlike a barrier we <em>want</em> vanilla to treat this as opaque, so it culls
 * neighbouring block faces and whole chunk sections behind it — that culling is
 * free correctness on top of the depth punch, not a conflict with it.</p>
 */
public class SkyboxBlock extends Block {

    public static final MapCodec<SkyboxBlock> CODEC = simpleCodec(SkyboxBlock::new);

    public SkyboxBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /** No baked model — the depth punch is the entire visual. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /**
     * Full brightness, so the block casts no ambient-occlusion darkening onto the
     * blocks around the hole. Vanilla {@code BarrierBlock} does the same.
     */
    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }
}
