package games.brennan.dungeontrain.train;

import java.util.Locale;
import java.util.Optional;

/**
 * How a <b>Half pair</b> ({@link HalfCarriageSelection}) closes the gap between its two halves.
 *
 * <p>Two Half boxes are each the long portal corridor's length ({@link ContentsSize#HALF}), which is
 * a block or two short of half a group — 13 + 13 of 27 at the default nine-long carriages, 12 + 12 of
 * 24 at eight. That {@link #gap} is where a portal's centre wall stands; a Half pair has no wall, so
 * one of these fills it:</p>
 *
 * <ul>
 *   <li>{@link #WALL} — the first half's last slice is repeated across the gap: a thicker shared end
 *       wall, doorway and all.</li>
 *   <li>{@link #BRIDGE} — a floor of the stage's plank slab across the gap, open above.</li>
 *   <li>{@link #SHORT} — the halves abut and the group is that much shorter; the leftover falls
 *       between groups, past the front pad.</li>
 *   <li>{@link #RANDOM} — one of the three above, seeded per group.</li>
 * </ul>
 *
 * <p>No Minecraft types, so the layout arithmetic unit-tests without a bootstrap.</p>
 */
public enum HalfJoinMode {
    WALL("wall"),
    BRIDGE("bridge"),
    SHORT("short"),
    RANDOM("random");

    /** The concrete modes {@link #RANDOM} draws from. */
    private static final HalfJoinMode[] CONCRETE = {WALL, BRIDGE, SHORT};

    private final String key;

    HalfJoinMode(String key) {
        this.key = key;
    }

    /** The lowercase spelling used on disk and in commands. */
    public String key() {
        return key;
    }

    /** {@code raw} as a mode, case-insensitively; empty for null / unknown. */
    public static Optional<HalfJoinMode> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (HalfJoinMode m : values()) {
            if (m.key.equals(k)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /** This mode for one group: {@link #RANDOM} resolves by {@code (seed, groupIndex)}, the rest are themselves. */
    public HalfJoinMode resolve(long seed, long groupIndex) {
        if (this != RANDOM) return this;
        long h = (seed ^ 0x48414C464A4F494EL) + groupIndex * 0x9E3779B97F4A7C15L; // "HALFJOIN"
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return CONCRETE[(int) Math.floorMod(h, (long) CONCRETE.length)];
    }

    /** Blocks between the two halves when laid end to end in a {@code runLength} group; negative = they don't fit. */
    public static int gap(int runLength, int halfLength) {
        return runLength - 2 * halfLength;
    }

    /** X offset of the second half from the run's origin, for a concrete mode. */
    public int secondHalfOffset(int runLength, int halfLength) {
        return this == SHORT ? halfLength : halfLength + Math.max(0, gap(runLength, halfLength));
    }

    /** How much shorter the group's sub-level is — the gap for {@link #SHORT}, 0 otherwise. */
    public int shortening(int runLength, int halfLength) {
        return this == SHORT ? Math.max(0, gap(runLength, halfLength)) : 0;
    }
}
