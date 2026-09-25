package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Two-space blocks inside a variant cell. A variant cell rolls one
 * {@link BlockState}; for a door, tall plant or bed that state is only one
 * half, so placement also writes the partner half at {@link #partnerOffset}.
 *
 * <ul>
 *   <li>{@code DOUBLE_BLOCK_HALF} (doors, tall grass / flowers, pitcher plant,
 *       small dripleaf, tall seagrass): lower → partner above, upper → below.</li>
 *   <li>{@code BED_PART} (beds): foot → partner one step toward {@code FACING},
 *       head → one step away.</li>
 * </ul>
 *
 * <p>Pure state logic — no level access — so every placer and the editor
 * menu share one definition of "multi-space".</p>
 */
public final class MultiBlockFootprint {

    private MultiBlockFootprint() {}

    /** True when {@code state} is one half of a two-space block. */
    public static boolean isMultiSpace(@Nullable BlockState state) {
        if (state == null) return false;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return true;
        return state.hasProperty(BlockStateProperties.BED_PART)
            && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
    }

    /** Offset from {@code state}'s space to its partner half, or {@code null} for a single-space block. */
    @Nullable
    public static BlockPos partnerOffset(@Nullable BlockState state) {
        if (state == null) return null;
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                ? BlockPos.ZERO.above() : BlockPos.ZERO.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction toward = state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                ? facing : facing.getOpposite();
            return BlockPos.ZERO.relative(toward);
        }
        return null;
    }

    /** The other half of {@code state} (same block, flipped half / part); {@code state} itself when single-space. */
    public static BlockState partnerState(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf h = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            return state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                h == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            BedPart p = state.getValue(BlockStateProperties.BED_PART);
            return state.setValue(BlockStateProperties.BED_PART, p == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
        }
        return state;
    }

    /**
     * Vertical-mirror twin for a variant entry: a lone door half flips
     * lower ↔ upper so its partner lands on the mirrored side. Beds and
     * single-space blocks are returned unchanged.
     */
    public static BlockState verticalFlip(BlockState state) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return state;
        return partnerState(state);
    }

    /** True when {@code v} places a multi-space block (not a mob, lock ref or empty placeholder). */
    public static boolean isMultiEntry(VariantState v) {
        return v != null && !v.isMob() && !v.isGroupRef()
            && !CarriageVariantBlocks.isEmptyPlaceholder(v.state())
            && isMultiSpace(v.state());
    }

    /**
     * The cell's footprint for single-space picks: the partner offset of the
     * first multi-space entry in {@code states}, or {@code null} when the
     * cell holds no multi-space entry (singles then behave as they always did).
     */
    @Nullable
    public static BlockPos cellFootprint(List<VariantState> states) {
        for (VariantState v : states) {
            if (isMultiEntry(v)) return partnerOffset(v.state());
        }
        return null;
    }

    /**
     * The variant cell that owns {@code local}: {@code local} itself when it is a cell, otherwise
     * the neighbouring cell whose two-space footprint reaches into it (a door cell's top half), so
     * the whole two-space area opens the same Z menu. {@code local} unchanged when neither.
     */
    public static BlockPos ownerCell(java.util.function.Function<BlockPos, List<VariantState>> statesAt,
                                     BlockPos local) {
        List<VariantState> own = statesAt.apply(local);
        if (own != null && !own.isEmpty()) return local;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos candidate = local.relative(d);
            List<VariantState> states = statesAt.apply(candidate);
            if (states == null || states.isEmpty()) continue;
            BlockPos footprint = cellFootprint(states);
            if (footprint != null && candidate.offset(footprint).equals(local)) return candidate;
        }
        return local;
    }

    /** {@link #ownerCell(java.util.function.Function, BlockPos)} over a plot's cells. */
    public static BlockPos ownerCell(BlockVariantPlot plot, BlockPos local) {
        return ownerCell(plot::statesAt, local);
    }

    /** True when the cell's first entry is multi-space — drives {@link VariantSpan#resolve}. */
    public static boolean firstEntryIsMulti(List<VariantState> states) {
        return !states.isEmpty() && isMultiEntry(states.get(0));
    }
}
