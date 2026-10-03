package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

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
 * {@link BlockStateProperties#HORIZONTAL_AXIS} (X/Z) →
 * {@link BlockStateProperties#ROTATION_16} (floor heads, standing banners and
 * signs — offered as 8 compass slots, see {@link #isCompass}). First match wins.
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

    /**
     * Mix constant for the upside-down flip roll on stairs / trapdoors
     * ({@link BlockStateProperties#HALF}) and slabs
     * ({@link BlockStateProperties#SLAB_TYPE}). Distinct from
     * {@link #ROT_SEED_SALT} so flip rolls don't correlate with FACING rolls
     * for the same block position.
     */
    private static final long FLIP_SEED_SALT = 0x2545F4914F6CDD1DL;

    /** Number of compass slots offered for a {@code ROTATION_16} block. */
    public static final int COMPASS_SLOTS = 8;

    /**
     * Compass slot labels, index = slot. Slot {@code i} is
     * {@code ROTATION_16 = 2i}; vanilla rotation 0 faces south and the value
     * climbs clockwise, so the order runs S → SW → W → … → SE.
     */
    private static final String[] COMPASS_LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    /** JSON names for the compass slots — lowercase {@link #COMPASS_LABELS}. */
    private static final String[] COMPASS_JSON_NAMES = {"s", "sw", "w", "nw", "n", "ne", "e", "se"};

    private RotationApplier() {}

    /**
     * True when {@code base}'s rotation is the 16-step {@code ROTATION_16}
     * property (and nothing earlier in the probe order) — its
     * {@link VariantRotation#dirMask()} then holds 8 compass slots rather than
     * {@link Direction} ordinals.
     */
    public static boolean isCompass(BlockState base) {
        Rotatable r = findRotatable(base);
        return r != null && r.compass;
    }

    /** Slots a rotation mask can address for {@code base}: 8 for compass blocks, else 6. */
    public static int slotCount(BlockState base) {
        return isCompass(base) ? COMPASS_SLOTS : Direction.values().length;
    }

    /**
     * Short menu label for one slot of {@code base}'s rotation mask — a compass
     * point ("N", "NE", …) for compass blocks, else the axis short name.
     */
    public static String slotLabel(BlockState base, int slot) {
        if (isCompass(base)) {
            return slot >= 0 && slot < COMPASS_SLOTS ? COMPASS_LABELS[slot] : "?";
        }
        if (slot < 0 || slot >= Direction.values().length) return "?";
        return switch (Direction.values()[slot]) {
            case EAST -> "XU";
            case WEST -> "XD";
            case UP -> "YU";
            case DOWN -> "YD";
            case SOUTH -> "ZU";
            case NORTH -> "ZD";
        };
    }

    /** JSON name for compass slot {@code slot} ({@code "n"}, {@code "ne"}, …). */
    public static String compassJsonName(int slot) {
        return COMPASS_JSON_NAMES[slot];
    }

    /** Compass slot for a JSON name ({@code "ne"} etc., case-insensitive), or -1. */
    public static int compassSlotOf(String name) {
        if (name == null) return -1;
        String n = name.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < COMPASS_SLOTS; i++) {
            if (COMPASS_JSON_NAMES[i].equals(n)) return i;
        }
        return -1;
    }

    /**
     * Turn a compass mask by a vanilla {@link net.minecraft.world.level.block.Rotation}
     * — each quarter turn moves {@code ROTATION_16} by 4, i.e. 2 slots.
     */
    public static int rotateCompassMask(int mask, net.minecraft.world.level.block.Rotation rotation) {
        int out = 0;
        for (int slot = 0; slot < COMPASS_SLOTS; slot++) {
            if ((mask & (1 << slot)) == 0) continue;
            out |= 1 << (rotation.rotate(slot * 2, 16) / 2);
        }
        return out;
    }

    /**
     * Mirror a compass mask the way {@code state.mirror(...)} moves the block:
     * an X flip is vanilla {@link net.minecraft.world.level.block.Mirror#FRONT_BACK},
     * a Z flip {@link net.minecraft.world.level.block.Mirror#LEFT_RIGHT}. A Y flip
     * leaves a yaw unchanged.
     */
    public static int mirrorCompassMask(int mask, boolean flipX, boolean flipZ) {
        int out = 0;
        for (int slot = 0; slot < COMPASS_SLOTS; slot++) {
            if ((mask & (1 << slot)) == 0) continue;
            int rot = slot * 2;
            if (flipX) rot = net.minecraft.world.level.block.Mirror.FRONT_BACK.mirror(rot, 16);
            if (flipZ) rot = net.minecraft.world.level.block.Mirror.LEFT_RIGHT.mirror(rot, 16);
            out |= 1 << (rot / 2);
        }
        return out;
    }

    /**
     * True when {@code base} has at least one of FACING / HORIZONTAL_FACING
     * / AXIS / HORIZONTAL_AXIS — i.e. rotation can do something visible.
     * Used by the menu UI to hide rotation cells for non-rotatable blocks.
     */
    public static boolean canRotate(BlockState base) {
        return findRotatable(base) != null;
    }

    /**
     * True when {@code base} has either {@link BlockStateProperties#SLAB_TYPE}
     * (slabs) or {@link BlockStateProperties#HALF} (stairs, trapdoors) — i.e.
     * the half/orientation pill should be shown for this block.
     */
    public static boolean canFlip(BlockState base) {
        if (base == null) return false;
        return base.hasProperty(BlockStateProperties.SLAB_TYPE)
            || base.hasProperty(BlockStateProperties.HALF);
    }

    /**
     * Apply a deterministic rotation pick + half override.
     *
     * <p>halfMode semantics:
     * <ul>
     *   <li>{@link VariantHalf.Mode#TOP} / {@link VariantHalf.Mode#BOTTOM}
     *       — force the half explicitly, regardless of rotation mode.
     *       Author has authored a fixed orientation per entry.</li>
     *   <li>{@link VariantHalf.Mode#RANDOM} (the default) — defer to the
     *       rotation mode for v7 backwards-compat: only roll the flip when
     *       rotation is also RANDOM; LOCK / OPTIONS preserve the captured
     *       half. The JSON-read migration in {@code CarriageVariantBlocks}
     *       upgrades existing prefabs to explicit TOP/BOTTOM so this
     *       fallback only kicks in for freshly-captured entries that the
     *       author hasn't customised yet.</li>
     * </ul>
     */
    public static BlockState apply(
        BlockState base, VariantRotation rot, VariantHalf half,
        BlockPos localPos, long worldSeed, int carriageIndex, int lockId
    ) {
        return apply(base, rot, half, VariantActive.NONE, localPos, worldSeed, carriageIndex, lockId);
    }

    /**
     * Eight-arg apply: facing pick + half override + latching redstone-toggle
     * ({@link VariantActive}) pass. ACTIVE / INACTIVE force the block's
     * toggle property; RANDOM rolls it with its own salt via
     * {@link RedstoneToggle#apply}. Pass {@link VariantState#active()} so
     * each entry's authored mode is honoured.
     */
    public static BlockState apply(
        BlockState base, VariantRotation rot, VariantHalf half, VariantActive active,
        BlockPos localPos, long worldSeed, int carriageIndex, int lockId
    ) {
        if (rot == null) rot = VariantRotation.NONE;
        if (half == null) half = VariantHalf.NONE;
        BlockState facingApplied = applyFacing(base, rot, localPos, worldSeed, carriageIndex, lockId);
        BlockState halfApplied;
        if (half.mode() == VariantHalf.Mode.RANDOM
            && rot.mode() != VariantRotation.Mode.RANDOM) {
            // halfMode default + LOCK/OPTIONS rotation → preserve captured
            // half (v7 semantics).
            halfApplied = facingApplied;
        } else {
            halfApplied = applyHalf(facingApplied, half, localPos, worldSeed, carriageIndex, lockId);
        }
        return RedstoneToggle.apply(halfApplied, active, localPos, worldSeed, carriageIndex, lockId);
    }

    /**
     * Legacy 6-arg apply (rotation only). Preserves the v7 behaviour
     * exactly: RANDOM rotation rolls the half flip; LOCK / OPTIONS rotation
     * preserves the captured half. New callers should use the 7-arg
     * {@link #apply(BlockState, VariantRotation, VariantHalf, BlockPos, long, int, int)}
     * and pass {@link VariantState#half()} so each entry's authored
     * halfMode is honoured.
     */
    public static BlockState apply(
        BlockState base, VariantRotation rot,
        BlockPos localPos, long worldSeed, int carriageIndex, int lockId
    ) {
        if (rot == null) rot = VariantRotation.NONE;
        BlockState facingApplied = applyFacing(base, rot, localPos, worldSeed, carriageIndex, lockId);
        if (rot.mode() == VariantRotation.Mode.RANDOM) {
            return applyHalf(facingApplied, VariantHalf.NONE,
                localPos, worldSeed, carriageIndex, lockId);
        }
        return facingApplied;
    }

    /**
     * The original facing-only pick. Extracted so {@link #apply} can layer the
     * flip roll on top — keeps the determinism contract for facing
     * unchanged and avoids re-flowing the early-return paths.
     */
    private static BlockState applyFacing(
        BlockState base, VariantRotation rot,
        BlockPos localPos, long worldSeed, int carriageIndex, int lockId
    ) {
        if (rot.isDefault()) return base;
        Rotatable rotatable = findRotatable(base);
        if (rotatable == null) return base;

        int requestMask = rot.mode() == VariantRotation.Mode.RANDOM
            ? VariantRotation.ALL_DIRS_MASK
            : rot.dirMask();
        int validMask = rotatable.validDirMask & requestMask;
        if (validMask == 0) return base;

        int picked;
        if (rot.mode() == VariantRotation.Mode.LOCK) {
            picked = Integer.numberOfTrailingZeros(validMask);
        } else {
            picked = pickWeightedFromMask(validMask, rotatable.slotCount, localPos, worldSeed, carriageIndex, lockId);
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
        if (rotatable == null || rotatable.compass) return base;
        return applySlot(base, picked.ordinal());
    }

    /**
     * Slot-indexed apply — {@link Direction#ordinal()} for facing / axis
     * blocks, a compass slot for {@link #isCompass} blocks. Returns
     * {@code base} unchanged if the block isn't rotatable or {@code slot}
     * isn't valid for it.
     */
    public static BlockState applySlot(BlockState base, int slot) {
        Rotatable rotatable = findRotatable(base);
        if (rotatable == null || slot < 0 || slot >= rotatable.slotCount) return base;
        if ((rotatable.validDirMask & (1 << slot)) == 0) return base;
        return rotatable.applyTo(base, slot);
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

    /**
     * Build a {@link VariantRotation} that LOCKs to the facing the supplied
     * {@link BlockState} already carries. Used as the fallback for the
     * variant-add flow when no predecessor entry has a usable direction —
     * the freshly captured block keeps the direction it was placed with
     * rather than spawning with a random facing.
     *
     * <p>Property probe order matches {@link #findRotatable}:
     * FACING → HORIZONTAL_FACING → AXIS → HORIZONTAL_AXIS. For axis
     * properties the direction encoding is arbitrary (both ends of the axis
     * round-trip to the same {@link BlockState} via {@code applyTo}); we
     * pick the positive direction (X→EAST, Y→UP, Z→SOUTH) so the menu's
     * rotation-mode cell reads naturally for the author.</p>
     *
     * <p>Returns {@link VariantRotation#NONE} when the block exposes none of
     * these properties — locking would be a no-op since {@link #canRotate}
     * already returns {@code false} and the menu hides the rotation cells.</p>
     */
    public static VariantRotation lockToCurrent(BlockState base) {
        int slot = slotOf(base);
        return slot < 0 ? VariantRotation.NONE : new VariantRotation(VariantRotation.Mode.LOCK, 1 << slot);
    }

    /**
     * Result of {@link #orientToPredecessors}: the (possibly rotated)
     * {@link BlockState} together with the LOCK rotation that matches it.
     * Returned as a record so callers can apply both at once when building
     * a fresh {@link VariantState} — keeping state and rotation aligned
     * means the menu UI and dedup check stay consistent with spawn-time
     * behavior.
     */
    public record OrientedState(BlockState state, VariantRotation rotation) {}

    /**
     * Orient {@code newState} to match the most recent predecessor entry
     * whose state carries a usable direction. Walks {@code predecessors}
     * from last to first, skipping any predecessor that either lacks a
     * directional property or whose direction isn't valid for
     * {@code newState}'s rotation property. Returns {@code (newState
     * rotated to that direction, LOCK at that direction)} for the first
     * predecessor that satisfies both checks.
     *
     * <p>Falls back to {@code (newState, lockToCurrent(newState))} when no
     * predecessor offers a valid direction. Authors can still cycle the
     * rotation cell to RANDOM / OPTIONS after the fact.</p>
     */
    public static OrientedState orientToPredecessors(BlockState newState,
                                                     List<VariantState> predecessors) {
        if (newState == null) return new OrientedState(null, VariantRotation.NONE);
        if (predecessors != null) {
            int newValidMask = validDirMask(newState);
            boolean newCompass = isCompass(newState);
            for (int i = predecessors.size() - 1; i >= 0; i--) {
                VariantState prev = predecessors.get(i);
                if (prev == null) continue;
                // Compass slots and Direction ordinals share bit positions but
                // not meaning — only carry a facing across the same kind.
                if (isCompass(prev.state()) != newCompass) continue;
                int slot = slotOf(prev.state());
                if (slot < 0) continue;
                if ((newValidMask & (1 << slot)) == 0) continue;
                return new OrientedState(applySlot(newState, slot),
                                         new VariantRotation(VariantRotation.Mode.LOCK, 1 << slot));
            }
        }
        return new OrientedState(newState, lockToCurrent(newState));
    }

    /**
     * The mask slot {@code state} currently sits in, or -1 when it has no
     * rotation property. Compass blocks snap their 16-step rotation to the
     * nearest of the 8 slots.
     */
    private static int slotOf(BlockState state) {
        Rotatable r = findRotatable(state);
        if (r == null) return -1;
        if (r.compass) {
            int rot = state.getValue(BlockStateProperties.ROTATION_16);
            return Math.round(rot / 2.0f) % COMPASS_SLOTS;
        }
        Direction d = directionOf(state);
        return d == null ? -1 : d.ordinal();
    }

    @Nullable
    private static Direction directionOf(BlockState state) {
        if (state == null) return null;
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            return positiveDirectionForAxis(state.getValue(BlockStateProperties.AXIS));
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return positiveDirectionForAxis(state.getValue(BlockStateProperties.HORIZONTAL_AXIS));
        }
        return null;
    }

    private static Direction positiveDirectionForAxis(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.EAST;
            case Y -> Direction.UP;
            case Z -> Direction.SOUTH;
        };
    }

    // ---------- Internals ----------

    /**
     * Tagged union over the rotation property kinds. Stores the
     * property-valid slot mask and the apply lambda so callers don't need to
     * know which kind matched. Slots are {@link Direction#ordinal()} for the
     * facing / axis kinds and compass points for {@code ROTATION_16}.
     */
    private static final class Rotatable {
        final int validDirMask;
        final int slotCount;
        final boolean compass;
        final ApplyFn apply;

        Rotatable(int validDirMask, ApplyFn apply) {
            this(validDirMask, Direction.values().length, false, apply);
        }

        Rotatable(int validDirMask, int slotCount, boolean compass, ApplyFn apply) {
            this.validDirMask = validDirMask;
            this.slotCount = slotCount;
            this.compass = compass;
            this.apply = apply;
        }

        BlockState applyTo(BlockState base, int slot) {
            return apply.apply(base, slot);
        }
    }

    @FunctionalInterface
    private interface ApplyFn {
        BlockState apply(BlockState base, int slot);
    }

    @Nullable
    private static Rotatable findRotatable(BlockState base) {
        if (base == null) return null;
        if (base.hasProperty(BlockStateProperties.FACING)) {
            DirectionProperty p = BlockStateProperties.FACING;
            return new Rotatable(maskOfPropertyValues(p), (b, s) -> b.setValue(p, Direction.values()[s]));
        }
        if (base.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            DirectionProperty p = BlockStateProperties.HORIZONTAL_FACING;
            return new Rotatable(maskOfPropertyValues(p), (b, s) -> b.setValue(p, Direction.values()[s]));
        }
        if (base.hasProperty(BlockStateProperties.AXIS)) {
            EnumProperty<Direction.Axis> p = BlockStateProperties.AXIS;
            int mask = maskOfAxisProperty(p);
            return new Rotatable(mask, (b, s) -> b.setValue(p, Direction.values()[s].getAxis()));
        }
        if (base.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            EnumProperty<Direction.Axis> p = BlockStateProperties.HORIZONTAL_AXIS;
            int mask = maskOfAxisProperty(p);
            return new Rotatable(mask, (b, s) -> b.setValue(p, Direction.values()[s].getAxis()));
        }
        if (base.hasProperty(BlockStateProperties.ROTATION_16)) {
            return new Rotatable((1 << COMPASS_SLOTS) - 1, COMPASS_SLOTS, true,
                (b, s) -> b.setValue(BlockStateProperties.ROTATION_16, s * 2));
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

    /**
     * Uniform pick from the bit-set slots. Lock-group cells share the same
     * seed source. Slots are walked in ascending order, so for facing blocks
     * the pick matches the historical {@link Direction}-ordinal walk exactly.
     */
    private static int pickWeightedFromMask(int validMask, int slotCount, BlockPos localPos,
                                            long worldSeed, int carriageIndex, int lockId) {
        List<Integer> options = new ArrayList<>(slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            if ((validMask & (1 << slot)) != 0) options.add(slot);
        }
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

    /**
     * Apply the per-entry {@link VariantHalf} override to a block that
     * exposes {@link BlockStateProperties#HALF} (stairs, trapdoors) or
     * {@link BlockStateProperties#SLAB_TYPE} (slabs). Returns {@code state}
     * unchanged when the block has neither property — the override is a
     * no-op for non-flippable blocks.
     *
     * <ul>
     *   <li>{@link VariantHalf.Mode#TOP} — force top half.</li>
     *   <li>{@link VariantHalf.Mode#BOTTOM} — force bottom half.</li>
     *   <li>{@link VariantHalf.Mode#RANDOM} — roll TOP/BOTTOM with the
     *       existing {@link #FLIP_SEED_SALT} seed so the historical
     *       per-position randomisation is preserved bit-for-bit.</li>
     * </ul>
     *
     * <p>SLAB_TYPE rolls between TOP and BOTTOM only — DOUBLE is never
     * selected. A captured DOUBLE slab demotes to TOP/BOTTOM here under
     * any non-default mode. Authors who need a stable DOUBLE slab should
     * leave halfMode at its default ({@link VariantHalf.Mode#RANDOM}) when
     * the captured state isn't DOUBLE, or capture a DOUBLE state and rely
     * on the JSON migration to keep RANDOM (which on a DOUBLE block still
     * runs the flip roll — same behaviour as v7).</p>
     */
    public static BlockState applyHalf(BlockState state, VariantHalf half,
                                       BlockPos localPos, long worldSeed,
                                       int carriageIndex, int lockId) {
        if (state == null) return null;
        if (half == null) half = VariantHalf.NONE;
        boolean hasHalf = state.hasProperty(BlockStateProperties.HALF);
        boolean hasSlab = state.hasProperty(BlockStateProperties.SLAB_TYPE);
        if (!hasHalf && !hasSlab) return state;

        boolean top;
        switch (half.mode()) {
            case TOP -> top = true;
            case BOTTOM -> top = false;
            case RANDOM -> {
                long posOrLock = lockId > 0
                    ? (long) lockId * 0xBF58476D1CE4E5B9L
                    : (((long) localPos.getX() * 31L + localPos.getY()) * 31L + localPos.getZ()) * 0xBF58476D1CE4E5B9L;
                long seed = worldSeed
                    ^ ((long) carriageIndex * 0x9E3779B97F4A7C15L)
                    ^ posOrLock
                    ^ FLIP_SEED_SALT;
                top = new Random(seed).nextBoolean();
            }
            default -> top = false;
        }

        BlockState out = state;
        if (hasHalf) {
            out = out.setValue(BlockStateProperties.HALF, top ? Half.TOP : Half.BOTTOM);
        }
        if (hasSlab) {
            out = out.setValue(BlockStateProperties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
        }
        return out;
    }
}
