package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure-data rotation applier — given a {@link BlockState} and a
 * {@link VariantRotation} config, produce a possibly-rotated copy. Used at
 * spawn time by the four {@code applyVariantBlocks} sites and at edit time
 * by {@link VariantEditorPreviewTicker} for the editor world-block preview.
 *
 * <p>Property probe order: {@link BlockStateProperties#FACING} (all 6) →
 * {@link BlockStateProperties#HORIZONTAL_FACING} (4 horizontal) →
 * {@link BlockStateProperties#AXIS} (X/Y/Z) →
 * {@link BlockStateProperties#HORIZONTAL_AXIS} (X/Z). First match wins.
 * Blocks with none of these are returned unchanged — see
 * {@link #canRotate} for the client-side gate that hides rotation cells
 * for such blocks.</p>
 *
 * <p>Determinism contract: same {@code (worldSeed, carriageIndex,
 * localPos|lockId, dirMask)} → same picked direction across reloads. Seeded
 * with a different mix constant from the state picker so rotation rolls
 * don't correlate with which state was picked.</p>
 */
public final class RotationApplier {

    /** Mix constant differing from the state-picker's so rolls are independent. */
    private static final long ROT_SEED_SALT = 0x94D049BB133111EBL;

    private RotationApplier() {}

    /**
     * True when {@code base} has at least one of FACING / HORIZONTAL_FACING
     * / AXIS / HORIZONTAL_AXIS — i.e. rotation can do something visible.
     * Used by the menu UI to hide rotation cells for non-rotatable blocks.
     */
    public static boolean canRotate(BlockState base) {
        return findRotatable(base) != null;
    }

    /**
     * Apply a deterministic rotation pick. Returns {@code base} unchanged
     * when (a) {@code rot.isDefault()}, (b) the block exposes no rotation
     * property, or (c) the chosen property has no valid direction in the
     * configured mask.
     */
    public static BlockState apply(
        BlockState base, VariantRotation rot,
        BlockPos localPos, long worldSeed, int carriageIndex, int lockId
    ) {
        if (rot == null || rot.isDefault()) return base;
        Rotatable rotatable = findRotatable(base);
        if (rotatable == null) return base;

        int requestMask = rot.mode() == VariantRotation.Mode.RANDOM
            ? VariantRotation.ALL_DIRS_MASK
            : rot.dirMask();
        int validMask = rotatable.validDirMask & requestMask;
        if (validMask == 0) return base;

        Direction picked;
        if (rot.mode() == VariantRotation.Mode.LOCK) {
            picked = directionFromLowestBit(validMask);
        } else {
            picked = pickWeightedFromMask(validMask, localPos, worldSeed, carriageIndex, lockId);
        }
        return rotatable.applyTo(base, picked);
    }

    /**
     * Direct-direction apply — for the editor preview ticker which has
     * already chosen which direction to show. Returns {@code base} unchanged
     * if the block isn't rotatable or {@code picked} isn't valid for its
     * property.
     */
    public static BlockState applyDirection(BlockState base, Direction picked) {
        if (picked == null) return base;
        Rotatable rotatable = findRotatable(base);
        if (rotatable == null) return base;
        if ((rotatable.validDirMask & VariantRotation.maskOf(picked)) == 0) return base;
        return rotatable.applyTo(base, picked);
    }

    /**
     * For the editor preview: which directions are valid for this block's
     * rotation property? Returns the full 6-bit mask if no rotation property
     * — caller can decide whether to filter.
     */
    public static int validDirMask(BlockState base) {
        Rotatable r = findRotatable(base);
        return r == null ? VariantRotation.ALL_DIRS_MASK : r.validDirMask;
    }

    // ---------- Internals ----------

    /**
     * Tagged union over the four rotation property kinds. Stores the
     * property-valid direction mask and the apply lambda so callers don't
     * need to know which kind matched.
     */
    private static final class Rotatable {
        final int validDirMask;
        final ApplyFn apply;

        Rotatable(int validDirMask, ApplyFn apply) {
            this.validDirMask = validDirMask;
            this.apply = apply;
        }

        BlockState applyTo(BlockState base, Direction d) {
            return apply.apply(base, d);
        }
    }

    @FunctionalInterface
    private interface ApplyFn {
        BlockState apply(BlockState base, Direction picked);
    }

    @Nullable
    private static Rotatable findRotatable(BlockState base) {
        if (base == null) return null;
        if (base.hasProperty(BlockStateProperties.FACING)) {
            DirectionProperty p = BlockStateProperties.FACING;
            return new Rotatable(maskOfPropertyValues(p), (b, d) -> b.setValue(p, d));
        }
        if (base.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            DirectionProperty p = BlockStateProperties.HORIZONTAL_FACING;
            return new Rotatable(maskOfPropertyValues(p), (b, d) -> b.setValue(p, d));
        }
        if (base.hasProperty(BlockStateProperties.AXIS)) {
            EnumProperty<Direction.Axis> p = BlockStateProperties.AXIS;
            int mask = maskOfAxisProperty(p);
            return new Rotatable(mask, (b, d) -> b.setValue(p, d.getAxis()));
        }
        if (base.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            EnumProperty<Direction.Axis> p = BlockStateProperties.HORIZONTAL_AXIS;
            int mask = maskOfAxisProperty(p);
            return new Rotatable(mask, (b, d) -> b.setValue(p, d.getAxis()));
        }
        return null;
    }

    private static int maskOfPropertyValues(DirectionProperty p) {
        int mask = 0;
        for (Direction d : p.getPossibleValues()) {
            mask |= VariantRotation.maskOf(d);
        }
        return mask;
    }

    /**
     * For AXIS / HORIZONTAL_AXIS properties: each axis allows 2 of the 6
     * directions (the positive and negative pair). Returns the mask of all
     * directions that map to one of the property's allowed axes.
     */
    private static int maskOfAxisProperty(EnumProperty<Direction.Axis> p) {
        int mask = 0;
        java.util.Collection<Direction.Axis> axes = p.getPossibleValues();
        for (Direction d : Direction.values()) {
            if (axes.contains(d.getAxis())) mask |= VariantRotation.maskOf(d);
        }
        return mask;
    }

    private static Direction directionFromLowestBit(int mask) {
        // mask is guaranteed non-zero by callers; lowest set bit's index
        // matches Direction.ordinal() (DOWN=0..EAST=5).
        int ord = Integer.numberOfTrailingZeros(mask);
        return Direction.values()[ord];
    }

    /** Uniform pick from the bit-set directions. Lock-group cells share the same seed source. */
    private static Direction pickWeightedFromMask(int validMask, BlockPos localPos,
                                                  long worldSeed, int carriageIndex, int lockId) {
        List<Direction> options = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            if ((validMask & VariantRotation.maskOf(d)) != 0) options.add(d);
        }
        if (options.isEmpty()) return null;
        long posOrLock = lockId > 0
            ? (long) lockId * 0xBF58476D1CE4E5B9L
            : (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ()) * 0xBF58476D1CE4E5B9L;
        long seed = worldSeed
            ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
            ^ posOrLock
            ^ ROT_SEED_SALT;
        int idx = new Random(seed).nextInt(options.size());
        return options.get(idx);
    }
}
