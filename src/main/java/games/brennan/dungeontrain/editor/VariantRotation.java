package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;

import java.util.EnumSet;
import java.util.Set;

/**
 * Per-{@link VariantState} rotation config: the spawn-time facing strategy
 * for a candidate. {@link Mode#LOCK} pins to one direction;
 * {@link Mode#RANDOM} draws over all 6 directions; {@link Mode#OPTIONS}
 * draws over an author-selected subset.
 *
 * <p>Direction set is a 6-bit mask over {@link Direction#ordinal()} (vanilla
 * order: DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5). Plain {@code int}
 * round-trips cleanly through wire / JSON and gives free equals/hashCode for
 * the enclosing record.
 *
 * <p>Canonical-constructor invariants:
 * <ul>
 *   <li>{@link Mode#LOCK} keeps only the lowest set bit (mask 0 falls back
 *       to {@link #NONE}).</li>
 *   <li>{@link Mode#OPTIONS} with no bits set falls back to {@link #NONE}.</li>
 *   <li>{@link Mode#RANDOM} forces {@code dirMask = 0}.</li>
 * </ul>
 */
public record VariantRotation(Mode mode, int dirMask) {

    public enum Mode { LOCK, RANDOM, OPTIONS }

    /** Default: random rotation over the block's full property-valid set. */
    public static final VariantRotation NONE = new VariantRotation(Mode.RANDOM, 0);

    public static final int ALL_DIRS_MASK = 0b111111;

    public VariantRotation {
        if (mode == null) mode = Mode.RANDOM;
        dirMask = dirMask & ALL_DIRS_MASK;
        switch (mode) {
            case LOCK -> {
                if (dirMask == 0) {
                    // Fall back to NONE shape rather than throw — keeps
                    // malformed JSON / packet payloads from crashing.
                    mode = Mode.RANDOM;
                } else {
                    dirMask = Integer.lowestOneBit(dirMask);
                }
            }
            case OPTIONS -> {
                if (dirMask == 0) mode = Mode.RANDOM;
            }
            case RANDOM -> dirMask = 0;
        }
    }

    /** True when this rotation is the no-op default — used to skip JSON emission and wire bytes. */
    public boolean isDefault() {
        return mode == Mode.RANDOM && dirMask == 0;
    }

    /** Convenience: derive the {@link Direction} set from {@link #dirMask}. */
    public Set<Direction> directions() {
        EnumSet<Direction> out = EnumSet.noneOf(Direction.class);
        for (Direction d : Direction.values()) {
            if ((dirMask & (1 << d.ordinal())) != 0) out.add(d);
        }
        return out;
    }

    /** Static helper: build a mask from a {@link Direction} iterable. */
    public static int maskOf(Iterable<Direction> dirs) {
        int m = 0;
        for (Direction d : dirs) m |= 1 << d.ordinal();
        return m;
    }

    /** Static helper: build a single-bit mask from one direction. */
    public static int maskOf(Direction d) {
        return 1 << d.ordinal();
    }

    public static VariantRotation lock(Direction d) {
        return new VariantRotation(Mode.LOCK, maskOf(d));
    }

    public static VariantRotation options(int dirMask) {
        return new VariantRotation(Mode.OPTIONS, dirMask);
    }
}
