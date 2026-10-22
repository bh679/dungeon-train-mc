package games.brennan.dungeontrain.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block-variant editor's <b>empty-placeholder sentinel</b> — the block that stands in a
 * variant cell for "leave this position empty / air at spawn time", and under a mob-entry slot
 * so the author has something to build against.
 *
 * <p>It replaced the vanilla command block in that role. A command block looked like a real
 * block and, worse, opened its GUI on right-click, so placing a block against a placeholder cell
 * meant holding shift. This block is a translucent ghost cube with a full outline (so it can be
 * targeted and built against) and <em>no</em> {@code useWithoutItem} — right-click falls straight
 * through to vanilla item placement. Its collision is scaffolding-style: solid only to something
 * standing on top of it, so the author can walk on placeholders but straight through them from the
 * side, and sneaking on one drops through.
 * It has no FACING property either, so the menu shows no rotation cells for a "nothing" row.</p>
 *
 * <p>It must never reach a live carriage: the variant appliers overwrite every sentinel cell with
 * AIR ({@code CarriageVariantBlocks.isEmptyPlaceholder}), and
 * {@code train.VariantPlaceholderAirProcessor} airs any stray one left in a template stamp.
 * Legacy sidecars and template NBTs still carry command blocks; {@code VariantState}'s canonical
 * constructor normalises those to this block on load.</p>
 */
public class VariantPlaceholderBlock extends Block {

    public static final MapCodec<VariantPlaceholderBlock> CODEC = simpleCodec(VariantPlaceholderBlock::new);

    public VariantPlaceholderBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Solid only under an entity whose feet are on or above the top face and that isn't sneaking
     * to drop — the {@code ScaffoldingBlock} test. Everything else (walking in from the side,
     * standing inside one) passes through. The outline ({@code getShape}) stays a full cube.
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return context.isAbove(Shapes.block(), pos, true) && !context.isDescending()
            ? Shapes.block()
            : Shapes.empty();
    }

    /** Adjacent placeholders share no inner faces — the glass rule — so a run of them reads as one slab of ghost. */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        return adjacentState.is(this) || super.skipRendering(state, adjacentState, direction);
    }

    /** No ambient-occlusion darkening on the neighbours of something that means "nothing here". */
    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    /** Costs light what air costs it — the cell IS air at spawn, so the editor should light like it. */
    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
