package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Turns one rolled variant cell into the block writes it needs when the cell
 * holds a two-space block (door, bed, tall plant — {@link MultiBlockFootprint}).
 *
 * <p>Every placer used to write exactly one block per cell. They now loop over
 * {@link #expand}: a cell with no multi-space entry still yields that single
 * write, so behaviour there is unchanged. A cell with one yields up to two:</p>
 * <ul>
 *   <li><b>Multi-space pick</b> — the rolled half plus its partner half, the
 *       partner offset read off the <em>rotated</em> state so a randomly
 *       turned bed puts its head where its facing points. If that lands
 *       somewhere other than the cell's footprint space, the footprint space
 *       is cleared so a template half left there doesn't float.</li>
 *   <li><b>Single-space pick</b> — the two footprint spaces filled per the
 *       entry's {@link VariantSpan}.</li>
 *   <li><b>Empty placeholder / mob</b> — both spaces cleared.</li>
 * </ul>
 *
 * <p>Writes are in the cell's <b>local</b> frame, before any placement mirror
 * or flip — callers push each through their own local → world transform, the
 * same one the single write used, so mirrored stamps stay consistent.</p>
 */
public final class MultiBlockVariants {

    /** Salt so the "position R" coin never correlates with the cell's own pick. */
    private static final long POSITION_SALT = 0x5DEECE66DL;

    private MultiBlockVariants() {}

    /** Rolls an entry's final state (rotation / half / redstone toggle) at the cell. */
    @FunctionalInterface
    public interface Rotator {
        BlockState rotate(VariantState entry);
    }

    /**
     * One block write. {@code entry} is the variant it came from (its NBT and
     * loot link travel with it); {@code null} means "clear to air".
     */
    public record Write(BlockPos localPos, @Nullable VariantState entry, BlockState state) {
        public boolean isAir() {
            return entry == null;
        }

        static Write air(BlockPos localPos) {
            return new Write(localPos, null, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * The writes for a cell whose roll landed on {@code picked}.
     *
     * @param cell    the cell's full candidate list (for the footprint, the
     *                first-entry default and the {@code 2 / Random} re-roll)
     * @param picked  the resolved pick; {@code null} yields no writes
     * @param rotator rolls a concrete entry's final state — the placer's own
     *                {@code RotationApplier} call
     */
    public static List<Write> expand(List<VariantState> cell, @Nullable VariantState picked,
                                     BlockPos localPos, long worldSeed, int index, Rotator rotator) {
        if (picked == null) return List.of();
        BlockPos footprint = MultiBlockFootprint.cellFootprint(cell);
        boolean empty = picked.isMob() || CarriageVariantBlocks.isEmptyPlaceholder(picked.state());

        if (empty) {
            return footprint == null
                ? List.of(Write.air(localPos))
                : List.of(Write.air(localPos), Write.air(localPos.offset(footprint)));
        }

        BlockState rotated = rotator.rotate(picked);
        BlockPos partner = MultiBlockFootprint.partnerOffset(rotated);
        if (partner != null) {
            List<Write> out = new ArrayList<>(3);
            out.add(new Write(localPos, picked, rotated));
            if (footprint != null && !footprint.equals(partner)) {
                out.add(Write.air(localPos.offset(footprint)));
            }
            out.add(new Write(localPos.offset(partner), picked, MultiBlockFootprint.partnerState(rotated)));
            return out;
        }

        Write here = new Write(localPos, picked, rotated);
        if (footprint == null) return List.of(here);

        BlockPos second = localPos.offset(footprint);
        VariantSpan.Mode mode = picked.span().resolve(MultiBlockFootprint.firstEntryIsMulti(cell));
        if (mode == VariantSpan.Mode.ONE_RANDOM) {
            mode = positionCoin(localPos, worldSeed, index)
                ? VariantSpan.Mode.ONE_SECOND : VariantSpan.Mode.ONE_FIRST;
        }
        return switch (mode) {
            case ONE_SECOND -> List.of(Write.air(localPos), new Write(second, picked, rotated));
            case TWO_SAME -> List.of(here, new Write(second, picked, rotated));
            case TWO_RANDOM -> List.of(here, reroll(cell, picked, second, worldSeed, index, rotator));
            default -> List.of(here, Write.air(second));
        };
    }

    /**
     * {@code 2 / Random}: an independent weighted roll among the cell's
     * single-space entries, seeded at the second space so it never mirrors the
     * cell's own draw. May land on {@code picked} again.
     */
    private static Write reroll(List<VariantState> cell, VariantState picked, BlockPos second,
                                long worldSeed, int index, Rotator rotator) {
        List<VariantState> singles = singles(cell);
        if (singles.isEmpty()) return new Write(second, picked, rotator.rotate(picked));
        VariantState roll = singles.get(
            CarriageVariantBlocks.pickIndexWeighted(second, worldSeed, index, singles));
        if (CarriageVariantBlocks.isEmptyPlaceholder(roll.state())) return Write.air(second);
        return new Write(second, roll, rotator.rotate(roll));
    }

    /** The cell's single-space candidates: concrete blocks (and the empty placeholder), no mobs, refs or multis. */
    static List<VariantState> singles(List<VariantState> cell) {
        List<VariantState> out = new ArrayList<>(cell.size());
        for (VariantState v : cell) {
            if (v.isMob() || v.isGroupRef() || MultiBlockFootprint.isMultiSpace(v.state())) continue;
            out.add(v);
        }
        return out;
    }

    /** Seeded coin for {@code 1 / R}: {@code true} → the second space. */
    static boolean positionCoin(BlockPos localPos, long worldSeed, int index) {
        long posHash = (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ());
        long seed = worldSeed
            ^ ((long) index * 0x9E3779B97F4A7C15L)
            ^ (posHash * 0xBF58476D1CE4E5B9L)
            ^ POSITION_SALT;
        return new Random(seed).nextBoolean();
    }
}
