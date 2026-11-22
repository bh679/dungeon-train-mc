package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Keeps a cell's own orientation through a lock-group edit when the group's rotation is free.
 *
 * <p>A lock group shares one candidate list: an edit made in one cell's menu (add, remove, a weight,
 * a condition…) is written to every cell of the group. On Rot Free each cell also owns its rows'
 * rotation ({@link VariantRotation}) and upside-down flip ({@link VariantHalf}), so a sibling must
 * take the edited list <em>with its own orientation kept</em> — otherwise one cell's edit would
 * quietly reset every other cell's facing. A row that is new to the cell takes the cell's own
 * orientation from its other rows ({@link #orientToCell}). Pure and list-in / list-out; nothing
 * passed in changes.</p>
 */
public final class VariantRowOrientation {

    private VariantRowOrientation() {}

    /**
     * {@code edited} with each row's rotation and flip taken from the matching row of {@code own}.
     *
     * <p>Same row count (a weight, mode, condition or replace edit): rows match by position. A
     * different count (a row added or removed): rows match by content — the row with its rotation,
     * flip and weight set aside — first unused match wins. A row with no match (the one just
     * added) is oriented to this cell's own rows by {@link #orientToCell}.</p>
     */
    public static List<VariantState> keepOwnOrientation(List<VariantState> own, List<VariantState> edited) {
        if (own == null || own.isEmpty() || edited == null) return edited;
        List<VariantState> out = new ArrayList<>(edited.size());
        if (own.size() == edited.size()) {
            for (int i = 0; i < edited.size(); i++) out.add(withOrientationOf(edited.get(i), own.get(i)));
            return List.copyOf(out);
        }
        boolean[] used = new boolean[own.size()];
        for (VariantState row : edited) {
            VariantState key = contentKey(row);
            VariantState match = null;
            for (int j = 0; j < own.size(); j++) {
                if (!used[j] && contentKey(own.get(j)).equals(key)) {
                    used[j] = true;
                    match = own.get(j);
                    break;
                }
            }
            out.add(match == null ? orientToCell(row, own) : withOrientationOf(row, match));
        }
        return List.copyOf(out);
    }

    /**
     * A row new to a Rot Free cell, with the rotation and flip of the cell's own rows. The donor is,
     * most recent first within each tier:
     * <ol>
     *   <li>a row of the same block — its rotation and flip, as they are;</li>
     *   <li>a row of the most similar block type (the same block class, or the same block-state
     *       properties: stairs for stairs, slab for slab) — its rotation and flip, as they are;</li>
     *   <li>the most recent row whose facing the new block can take — locked to that facing, with
     *       its flip when both blocks have one.</li>
     * </ol>
     * No donor, or a block with nothing to rotate or flip: {@code row} unchanged. The row's block
     * state is left alone, like every other row this class touches; spawn and preview roll from the
     * rotation.
     */
    public static VariantState orientToCell(VariantState row, List<VariantState> cellRows) {
        if (row == null || cellRows == null || row.isMob() || row.isGroupRef()) return row;
        BlockState state = row.state();
        if (state == null || !(RotationApplier.canRotate(state) || RotationApplier.canFlip(state))) return row;

        VariantState donor = mostRecent(cellRows, state, VariantRowOrientation::sameBlock);
        if (donor == null) donor = mostRecent(cellRows, state, VariantRowOrientation::sameType);
        if (donor != null) return withOrientationOf(row, donor);

        return orientToFacing(row, cellRows);
    }

    /** Tier 3: lock to the most recent row's facing that the new block can take. */
    private static VariantState orientToFacing(VariantState row, List<VariantState> cellRows) {
        BlockState state = row.state();
        if (!RotationApplier.canRotate(state)) return row;
        int validMask = RotationApplier.validDirMask(state);
        boolean compass = RotationApplier.isCompass(state);
        for (int i = cellRows.size() - 1; i >= 0; i--) {
            VariantState prev = cellRows.get(i);
            if (!isDonor(prev) || RotationApplier.isCompass(prev.state()) != compass) continue;
            int slot = facingSlot(prev);
            if (slot < 0 || (validMask & (1 << slot)) == 0) continue;
            VariantState out = row.withRotation(new VariantRotation(VariantRotation.Mode.LOCK, 1 << slot));
            return RotationApplier.canFlip(prev.state()) && RotationApplier.canFlip(state)
                ? out.withHalf(prev.half()) : out;
        }
        return row;
    }

    /** The slot a row faces: its locked slot when it has one, else the facing its state was captured with. */
    private static int facingSlot(VariantState row) {
        VariantRotation rot = row.rotation();
        if (rot.mode() == VariantRotation.Mode.LOCK && Integer.bitCount(rot.dirMask()) == 1) {
            return Integer.numberOfTrailingZeros(rot.dirMask());
        }
        return RotationApplier.slotOf(row.state());
    }

    private static VariantState mostRecent(List<VariantState> rows, BlockState state,
                                           BiPredicate<BlockState, BlockState> similar) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            VariantState prev = rows.get(i);
            if (isDonor(prev) && similar.test(prev.state(), state)) return prev;
        }
        return null;
    }

    private static boolean sameBlock(BlockState a, BlockState b) {
        return a.getBlock() == b.getBlock();
    }

    private static boolean sameType(BlockState a, BlockState b) {
        return a.getBlock().getClass() == b.getBlock().getClass()
            // getProperties() is an unmodifiable view whose equals is identity: compare as sets.
            || Set.copyOf(a.getProperties()).equals(Set.copyOf(b.getProperties()));
    }

    /** A row whose block can lend a facing: not a mob, a group reference, or an empty cell. */
    private static boolean isDonor(VariantState row) {
        return row != null && !row.isMob() && !row.isGroupRef() && row.state() != null
            && !row.state().isAir() && !CarriageVariantBlocks.isEmptyPlaceholder(row.state());
    }

    private static VariantState withOrientationOf(VariantState row, VariantState source) {
        return row.withRotation(source.rotation()).withHalf(source.half());
    }

    /** What a row is, apart from the per-cell orientation and the weight an edit may have changed. */
    private static VariantState contentKey(VariantState row) {
        return row.withRotation(VariantRotation.NONE).withHalf(VariantHalf.NONE).withWeight(1);
    }
}
