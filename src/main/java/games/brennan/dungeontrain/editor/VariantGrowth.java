package games.brennan.dungeontrain.editor;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry <b>growth</b> for a column-forming block (vine, cave vines, ladder,
 * scaffolding, bamboo, dripstone, … — see {@link GrowthShapes#canGrow}). When
 * {@link #on} the placed block grows into a column of {@code min..max} blocks
 * (the cell counts as the first), rolled per spawn from the variant seed; the
 * column stops early at the first space it can't take ({@link GrowthPass}).
 *
 * <ul>
 *   <li>{@link #dir} — which way it grows. Only honoured for blocks that grow
 *       both ways ({@link GrowthShapes#allowedDirs}); the rest always grow their
 *       natural way.</li>
 *   <li>{@link #tip} — end the column with the block's tip form (cave vines head,
 *       bamboo leaves, dripstone point). Off ends it in a body block. Ignored
 *       for blocks with no distinct tip.</li>
 * </ul>
 *
 * <p>{@link #NONE} (off) is the default and is omitted from JSON, so older
 * templates spawn unchanged. Sidecar token: {@code "up 2-5"} / {@code "down 1-8 notip"}.</p>
 */
public record VariantGrowth(boolean on, int min, int max, Dir dir, boolean tip) {

    public enum Dir { UP, DOWN }

    /** Longest column an author can ask for. Fits the 5-bit wire field. */
    public static final int MAX_LENGTH = 32;

    /** Default: no growth. */
    public static final VariantGrowth NONE = new VariantGrowth(false, 1, 1, Dir.UP, true);

    public VariantGrowth {
        if (dir == null) dir = Dir.UP;
        min = clamp(min);
        max = clamp(max);
        if (max < min) max = min;
    }

    /** An enabled growth of {@code min..max} blocks. */
    public static VariantGrowth of(int min, int max, Dir dir, boolean tip) {
        return new VariantGrowth(true, min, max, dir, tip);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !on;
    }

    public VariantGrowth withOn(boolean newOn) {
        return new VariantGrowth(newOn, min, max, dir, tip);
    }

    public VariantGrowth withRange(int newMin, int newMax) {
        return new VariantGrowth(on, newMin, newMax, dir, tip);
    }

    public VariantGrowth withDir(Dir newDir) {
        return new VariantGrowth(on, min, max, newDir, tip);
    }

    public VariantGrowth withTip(boolean newTip) {
        return new VariantGrowth(on, min, max, dir, newTip);
    }

    /** Swap Up and Down — for a vertical mirror of the plot. */
    public VariantGrowth flipped() {
        return withDir(dir == Dir.UP ? Dir.DOWN : Dir.UP);
    }

    /**
     * Column length for one spawn, in {@code [min, max]}. Deterministic for a
     * given seed/cell/index; salted apart from the rotation / active / span rolls.
     */
    public int rollLength(long seed, long cellKey, int index) {
        if (max == min) return min;
        long h = seed * 0x9E3779B97F4A7C15L ^ cellKey * 0xC2B2AE3D27D4EB4FL ^ (index + 0x6A09E667L) * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return min + (int) Math.floorMod(h, (long) (max - min + 1));
    }

    /** Pack into one int for the edit / sync packets. */
    public int toInt() {
        return (on ? 1 : 0)
            | (dir == Dir.DOWN ? 1 << 1 : 0)
            | (tip ? 0 : 1 << 2)
            | ((min - 1) & 0x1F) << 3
            | ((max - 1) & 0x1F) << 8;
    }

    public static VariantGrowth fromInt(int bits) {
        return new VariantGrowth(
            (bits & 1) != 0,
            ((bits >>> 3) & 0x1F) + 1,
            ((bits >>> 8) & 0x1F) + 1,
            (bits & (1 << 1)) != 0 ? Dir.DOWN : Dir.UP,
            (bits & (1 << 2)) == 0);
    }

    /** Sidecar token, e.g. {@code "up 2-5"} or {@code "down 1-8 notip"}. */
    public String id() {
        String s = dir.name().toLowerCase(Locale.ROOT) + " " + min + "-" + max;
        return tip ? s : s + " notip";
    }

    /** Parse a sidecar token; {@code null} when malformed. */
    @Nullable
    public static VariantGrowth parse(@Nullable String token) {
        if (token == null) return null;
        String[] parts = token.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 2 || parts.length > 3) return null;
        Dir dir;
        if (parts[0].equals("up")) dir = Dir.UP;
        else if (parts[0].equals("down")) dir = Dir.DOWN;
        else return null;
        String[] range = parts[1].split("-");
        if (range.length != 2) return null;
        int min;
        int max;
        try {
            min = Integer.parseInt(range[0]);
            max = Integer.parseInt(range[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        boolean tip = true;
        if (parts.length == 3) {
            if (!parts[2].equals("notip")) return null;
            tip = false;
        }
        return of(min, max, dir, tip);
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(MAX_LENGTH, v));
    }
}
