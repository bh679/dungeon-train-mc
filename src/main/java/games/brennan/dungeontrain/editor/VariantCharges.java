package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry <b>charge range</b> for a respawn anchor row ({@link RespawnAnchorBlock}, the
 * {@code charges} property, 0..{@value #MAX}).
 *
 * <ul>
 *   <li>{@link #AS_PLACED} ({@code ranged == false}) — the charge stored in the captured state.
 *       The default, omitted from JSON, so older templates spawn unchanged.</li>
 *   <li>{@code ranged} — rolled {@code min..max} per spawn from the variant seed (a cell in a
 *       lock group rolls with its group). {@code min == max} is a fixed charge.</li>
 * </ul>
 *
 * <p>Sidecar token: {@code "1-4"}; blank for the default.</p>
 */
public record VariantCharges(boolean ranged, int min, int max) {

    /** Most charges a respawn anchor holds. */
    public static final int MAX = RespawnAnchorBlock.MAX_CHARGES;

    /** Default: keep the captured charge. */
    public static final VariantCharges AS_PLACED = new VariantCharges(false, 1, MAX);

    /** Salt distinct from the rotation / half / active / growth / candle / pot rolls. */
    private static final long CHARGE_SEED_SALT = 0x5A17C3E9D2B48F61L;

    public VariantCharges {
        if (!ranged) {
            // One canonical as-placed value, so equals / isDefault never trip on a stale range.
            min = 1;
            max = MAX;
        } else {
            min = clamp(min);
            max = clamp(max);
            if (max < min) max = min;
        }
    }

    /** A ranged option rolling {@code min..max}, clamped to 0..{@value #MAX}. */
    public static VariantCharges range(int min, int max) {
        return new VariantCharges(true, min, max);
    }

    /** True for a respawn anchor — the gate for the Charge pill and the spawn pass. */
    public static boolean isAnchor(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof RespawnAnchorBlock
            && state.hasProperty(RespawnAnchorBlock.CHARGE);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return !ranged;
    }

    /** Return a copy with the range replaced; {@code min ≤ max} is kept by the constructor. */
    public VariantCharges withRange(int newMin, int newMax) {
        return new VariantCharges(ranged, newMin, newMax);
    }

    /**
     * {@code state} with a charge rolled from this range, deterministic for a given seed /
     * carriage / cell (or lock group). Unchanged for {@link #AS_PLACED} or a non-anchor.
     */
    public BlockState apply(BlockState state, long posOrLockKey, long worldSeed, int carriageIndex) {
        if (isDefault() || !isAnchor(state)) return state;
        return state.setValue(RespawnAnchorBlock.CHARGE, roll(posOrLockKey, worldSeed, carriageIndex));
    }

    int roll(long posOrLockKey, long worldSeed, int carriageIndex) {
        if (min == max) return min;
        long h = worldSeed ^ (long) carriageIndex * 0x9E3779B97F4A7C15L ^ posOrLockKey ^ CHARGE_SEED_SALT;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return min + (int) Math.floorMod(h, (long) (max - min + 1));
    }

    /** Pack into one int for the edit / sync packets: bit 0 ranged, bits 1-3 min, bits 4-6 max. */
    public int toInt() {
        return (ranged ? 1 : 0) | (min & 0x7) << 1 | (max & 0x7) << 4;
    }

    public static VariantCharges fromInt(int bits) {
        return new VariantCharges((bits & 1) != 0, (bits >>> 1) & 0x7, (bits >>> 4) & 0x7);
    }

    /** Sidecar / NBT token: {@code "1-4"}, or {@code ""} for the default. */
    public String id() {
        return isDefault() ? "" : min + "-" + max;
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #AS_PLACED}. */
    @Nullable
    public static VariantCharges parse(@Nullable String token) {
        if (token == null || token.isBlank()) return AS_PLACED;
        String[] range = token.trim().toLowerCase(Locale.ROOT).split("-");
        if (range.length != 2) return null;
        try {
            int min = Integer.parseInt(range[0].trim());
            int max = Integer.parseInt(range[1].trim());
            if (min < 0 || max > MAX || min > max) return null;
            return range(min, max);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(MAX, v));
    }
}
