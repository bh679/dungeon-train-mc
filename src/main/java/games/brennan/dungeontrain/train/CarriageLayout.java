package games.brennan.dungeontrain.train;

import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * How one train group is filled — drawn per group by the {@link LayoutWeights}:
 *
 * <ul>
 *   <li>{@link #ROOMS} — one Room template per carriage slot (three in a default group), each its own
 *       weighted pick: the train as it has always been.</li>
 *   <li>{@link #HALVES} — two Half templates end to end, each its own weighted pick
 *       ({@link HalfCarriageSelection}).</li>
 *   <li>{@link #GROUP} — one Group template the length of the whole group
 *       ({@link FullCarriageSelection}).</li>
 * </ul>
 *
 * <p>A layout whose pool has nothing to offer this group falls back to {@link #ROOMS}. No Minecraft
 * types, so the draw unit-tests without a bootstrap.</p>
 */
public enum CarriageLayout {
    ROOMS("rooms"),
    HALVES("halves"),
    GROUP("group");

    private static final long SALT = 0x4C41594F55544452L; // "LAYOUTDR"

    private final String key;

    CarriageLayout(String key) {
        this.key = key;
    }

    /** The lowercase spelling used on disk and in commands. */
    public String key() {
        return key;
    }

    public static Optional<CarriageLayout> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (CarriageLayout l : values()) {
            if (l.key.equals(k)) return Optional.of(l);
        }
        return Optional.empty();
    }

    /**
     * The seeded draw for group {@code groupIndex} — the same answer every time the group is
     * stamped. All weights zero (or negative) is {@link #ROOMS}.
     */
    public static CarriageLayout draw(long seed, long groupIndex, LayoutWeights weights) {
        int rooms = Math.max(0, weights.rooms());
        int halves = Math.max(0, weights.halves());
        int group = Math.max(0, weights.group());
        int total = rooms + halves + group;
        if (total <= 0) return ROOMS;
        int r = new Random((seed ^ SALT) + groupIndex * 0x9E3779B97F4A7C15L).nextInt(total);
        if (r < rooms) return ROOMS;
        if (r < rooms + halves) return HALVES;
        return GROUP;
    }
}
