package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.block.state.properties.Tilt;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Which blocks can {@linkplain VariantGrowth grow} into a column, and what each
 * block of that column looks like. Pure {@link BlockState} maths — the world
 * checks (free space, survival, bounds) live in {@link GrowthPass}.
 *
 * <p>A column is indexed from the cell ({@code 0}) outward. Most families have a
 * <b>body</b> form and a distinct <b>tip</b> form for the far end (cave vines
 * head, bamboo leaves, dripstone point); the rest repeat one block. Heads that
 * would keep growing on a random tick are placed fully aged, so a column on a
 * moving train keeps the length that was rolled.</p>
 */
public final class GrowthShapes {

    /** One kind of growing block. {@code natural} is null for blocks that grow both ways. */
    private enum Family {
        VINE(null, false),
        CAVE_VINES(VariantGrowth.Dir.DOWN, true),
        WEEPING_VINES(VariantGrowth.Dir.DOWN, true),
        TWISTING_VINES(VariantGrowth.Dir.UP, true),
        KELP(VariantGrowth.Dir.UP, true),
        LADDER(null, false),
        CHAIN(null, false),
        SCAFFOLDING(null, false),
        BAMBOO(VariantGrowth.Dir.UP, true),
        SUGAR_CANE(VariantGrowth.Dir.UP, false),
        CACTUS(VariantGrowth.Dir.UP, false),
        BIG_DRIPLEAF(VariantGrowth.Dir.UP, true),
        DRIPSTONE(null, true),
        CHORUS(VariantGrowth.Dir.UP, true);

        @Nullable final VariantGrowth.Dir natural;
        final boolean hasTip;

        Family(@Nullable VariantGrowth.Dir natural, boolean hasTip) {
            this.natural = natural;
            this.hasTip = hasTip;
        }
    }

    private GrowthShapes() {}

    @Nullable
    private static Family familyOf(@Nullable BlockState s) {
        if (s == null) return null;
        Block b = s.getBlock();
        if (b == Blocks.VINE) return hasSideFace(s) ? Family.VINE : null;
        if (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT) return Family.CAVE_VINES;
        if (b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT) return Family.WEEPING_VINES;
        if (b == Blocks.TWISTING_VINES || b == Blocks.TWISTING_VINES_PLANT) return Family.TWISTING_VINES;
        if (b == Blocks.KELP || b == Blocks.KELP_PLANT) return Family.KELP;
        if (b == Blocks.LADDER) return Family.LADDER;
        if (b == Blocks.CHAIN) return s.getValue(ChainBlock.AXIS) == Direction.Axis.Y ? Family.CHAIN : null;
        if (b == Blocks.SCAFFOLDING) return Family.SCAFFOLDING;
        if (b == Blocks.BAMBOO) return Family.BAMBOO;
        if (b == Blocks.SUGAR_CANE) return Family.SUGAR_CANE;
        if (b == Blocks.CACTUS) return Family.CACTUS;
        if (b == Blocks.BIG_DRIPLEAF || b == Blocks.BIG_DRIPLEAF_STEM) return Family.BIG_DRIPLEAF;
        if (b == Blocks.POINTED_DRIPSTONE) return Family.DRIPSTONE;
        if (b == Blocks.CHORUS_PLANT || b == Blocks.CHORUS_FLOWER) return Family.CHORUS;
        return null;
    }

    /** True when {@code state} can grow into a column — the gate for the menu's Grow pill. */
    public static boolean canGrow(@Nullable BlockState state) {
        return familyOf(state) != null;
    }

    /** True when the author may pick Up or Down; false when the block only grows its natural way. */
    public static boolean growsBothWays(@Nullable BlockState state) {
        Family f = familyOf(state);
        return f != null && f.natural == null;
    }

    /** True when the column ends in a distinct tip block, so the Tip toggle means something. */
    public static boolean hasTip(@Nullable BlockState state) {
        Family f = familyOf(state);
        return f != null && f.hasTip;
    }

    /** The direction the column actually grows: the author's pick, or the block's natural way. */
    public static VariantGrowth.Dir effectiveDir(BlockState state, VariantGrowth.Dir requested) {
        Family f = familyOf(state);
        return f == null || f.natural == null ? requested : f.natural;
    }

    /** True for scaffolding, whose support distance {@link GrowthPass} re-derives in the world. */
    public static boolean isScaffolding(BlockState state) {
        return familyOf(state) == Family.SCAFFOLDING;
    }

    /** True when {@code existing} is a space the column may grow into (air; a water source for kelp). */
    public static boolean isFree(BlockState entry, BlockState existing) {
        if (familyOf(entry) == Family.KELP) {
            return existing.is(Blocks.WATER) && existing.getFluidState().isSourceOfType(Fluids.WATER);
        }
        return existing.isAir();
    }

    /**
     * The states of a whole column of {@code length} blocks grown from {@code entry}
     * ({@code [0]} is the cell itself). {@code length < 2} returns just the entry.
     */
    public static List<BlockState> column(BlockState entry, VariantGrowth.Dir requestedDir, int length, boolean tip) {
        Family f = familyOf(entry);
        List<BlockState> out = new ArrayList<>(Math.max(1, length));
        if (f == null || length < 2) {
            out.add(entry);
            return out;
        }
        VariantGrowth.Dir dir = effectiveDir(entry, requestedDir);
        boolean withTip = tip && f.hasTip;
        for (int i = 0; i < length; i++) {
            out.add(stateAt(f, entry, dir, i, length, withTip));
        }
        return out;
    }

    private static BlockState stateAt(Family f, BlockState entry, VariantGrowth.Dir dir, int i, int len, boolean tip) {
        boolean last = i == len - 1;
        return switch (f) {
            case VINE -> vineBody(entry);
            case CAVE_VINES -> {
                boolean berries = entry.getValue(CaveVines.BERRIES);
                yield last && tip
                    ? Blocks.CAVE_VINES.defaultBlockState().setValue(CaveVines.BERRIES, berries)
                        .setValue(GrowingPlantHeadBlock.AGE, GrowingPlantHeadBlock.MAX_AGE)
                    : Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, berries);
            }
            case WEEPING_VINES -> headOrBody(last && tip, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT);
            case TWISTING_VINES -> headOrBody(last && tip, Blocks.TWISTING_VINES, Blocks.TWISTING_VINES_PLANT);
            case KELP -> headOrBody(last && tip, Blocks.KELP, Blocks.KELP_PLANT);
            case LADDER, CACTUS -> entry;
            case CHAIN -> entry.setValue(ChainBlock.AXIS, Direction.Axis.Y);
            case SUGAR_CANE -> entry.setValue(BlockStateProperties.AGE_15, 0);
            // Distance is re-derived against the world in GrowthPass; 0 is right for a floor column.
            case SCAFFOLDING -> entry.setValue(ScaffoldingBlock.DISTANCE, 0).setValue(ScaffoldingBlock.BOTTOM, false);
            case BAMBOO -> bamboo(entry, i, len, tip);
            case BIG_DRIPLEAF -> {
                Direction facing = entry.getValue(BigDripleafBlock.FACING);
                yield last && tip
                    ? Blocks.BIG_DRIPLEAF.defaultBlockState().setValue(BigDripleafBlock.FACING, facing)
                        .setValue(BlockStateProperties.TILT, Tilt.NONE)
                    : Blocks.BIG_DRIPLEAF_STEM.defaultBlockState().setValue(BigDripleafBlock.FACING, facing);
            }
            case DRIPSTONE -> Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, dir == VariantGrowth.Dir.UP ? Direction.UP : Direction.DOWN)
                .setValue(PointedDripstoneBlock.THICKNESS, dripstoneThickness(i, len, tip));
            case CHORUS -> last && tip
                ? Blocks.CHORUS_FLOWER.defaultBlockState().setValue(ChorusFlowerBlock.AGE, ChorusFlowerBlock.DEAD_AGE)
                : Blocks.CHORUS_PLANT.defaultBlockState()
                    .setValue(PipeBlock.DOWN, i > 0 || entry.is(Blocks.CHORUS_PLANT) && entry.getValue(PipeBlock.DOWN))
                    .setValue(PipeBlock.UP, !last || tip);
        };
    }

    private static BlockState headOrBody(boolean head, Block headBlock, Block bodyBlock) {
        return head
            ? headBlock.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, GrowingPlantHeadBlock.MAX_AGE)
            : bodyBlock.defaultBlockState();
    }

    /** Side faces kept, ceiling face dropped — a grown vine hangs off its wall, not the roof. */
    private static BlockState vineBody(BlockState entry) {
        return entry.setValue(VineBlock.UP, false);
    }

    private static boolean hasSideFace(BlockState vine) {
        return vine.getValue(VineBlock.NORTH) || vine.getValue(VineBlock.EAST)
            || vine.getValue(VineBlock.SOUTH) || vine.getValue(VineBlock.WEST);
    }

    /** Mature stalk (stage 1, so it never grows on its own); leaves on the top two when tipped. */
    private static BlockState bamboo(BlockState entry, int i, int len, boolean tip) {
        BambooLeaves leaves = BambooLeaves.NONE;
        if (tip && i == len - 1) leaves = BambooLeaves.LARGE;
        else if (tip && i == len - 2 && len >= 3) leaves = BambooLeaves.SMALL;
        return entry.setValue(BambooStalkBlock.LEAVES, leaves).setValue(BambooStalkBlock.STAGE, 1);
    }

    /**
     * Vanilla's shape from the attached end out: base, middle…, frustum, tip. Without a tip the
     * column ends at the frustum.
     */
    static DripstoneThickness dripstoneThickness(int i, int len, boolean tip) {
        int fromEnd = len - 1 - i;
        if (tip) {
            if (fromEnd == 0) return DripstoneThickness.TIP;
            if (fromEnd == 1) return DripstoneThickness.FRUSTUM;
        } else if (fromEnd == 0) {
            return DripstoneThickness.FRUSTUM;
        }
        return i == 0 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
    }
}
