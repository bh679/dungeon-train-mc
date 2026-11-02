package games.brennan.dungeontrain.editor;

import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Per-entry <b>candle count</b> for a candle row ({@link CandleBlock}, the
 * {@code candles} property, 1..{@value #MAX}). Lit / unlit rides the row's
 * {@link VariantActive} pill ({@link RedstoneToggle}).
 *
 * <ul>
 *   <li>{@link #AS_PLACED} ({@code 0}) — the count stored in the captured state. The default,
 *       omitted from JSON, so older templates spawn unchanged.</li>
 *   <li>{@link #RANDOM} ({@code -1}) — rolled 1..{@value #MAX} per spawn from the variant seed.
 *       What a freshly added candle row starts as.</li>
 *   <li>{@code 1..}{@value #MAX} — that many candles.</li>
 * </ul>
 *
 * <p>Sidecar token: {@code "random"} / {@code "3"}.</p>
 */
public record VariantCandles(int count) {

    /** Most candles one block holds. */
    public static final int MAX = 4;

    private static final int RANDOM_COUNT = -1;

    /** Default: keep the captured count. */
    public static final VariantCandles AS_PLACED = new VariantCandles(0);

    /** Roll the count per spawn. */
    public static final VariantCandles RANDOM = new VariantCandles(RANDOM_COUNT);

    /** Salt distinct from the rotation / half / active / growth rolls. */
    private static final long CANDLE_SEED_SALT = 0x3D4E5F60718293A4L;

    public VariantCandles {
        if (count < RANDOM_COUNT || count > MAX) count = 0;
    }

    /** A fixed count, clamped to 1..{@value #MAX}. */
    public static VariantCandles fixed(int count) {
        return new VariantCandles(Math.max(1, Math.min(MAX, count)));
    }

    /** True for a candle block — the gate for the Candles pill and the spawn pass. */
    public static boolean isCandle(@Nullable BlockState state) {
        return state != null && state.getBlock() instanceof CandleBlock
            && state.hasProperty(BlockStateProperties.CANDLES);
    }

    /** True when this is the no-op default — used to skip JSON / NBT emission. */
    public boolean isDefault() {
        return count == 0;
    }

    public boolean isRandom() {
        return count == RANDOM_COUNT;
    }

    /**
     * {@code state} with this option's count: the rolled one for {@link #RANDOM}, deterministic for a
     * given seed / carriage / cell (or lock group). Unchanged for {@link #AS_PLACED} or a non-candle.
     */
    public BlockState apply(BlockState state, long posOrLockKey, long worldSeed, int carriageIndex) {
        if (isDefault() || !isCandle(state)) return state;
        int n = isRandom() ? roll(posOrLockKey, worldSeed, carriageIndex) : count;
        return state.setValue(BlockStateProperties.CANDLES, n);
    }

    static int roll(long posOrLockKey, long worldSeed, int carriageIndex) {
        long h = worldSeed ^ (long) carriageIndex * 0x9E3779B97F4A7C15L ^ posOrLockKey ^ CANDLE_SEED_SALT;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return 1 + (int) Math.floorMod(h, (long) MAX);
    }

    /** Sidecar / wire token: {@code "random"}, {@code "3"}, or {@code ""} for the default. */
    public String id() {
        if (isDefault()) return "";
        return isRandom() ? "random" : Integer.toString(count);
    }

    /** Parse a token; {@code null} when malformed. {@code null} / blank parses to {@link #AS_PLACED}. */
    @Nullable
    public static VariantCandles parse(@Nullable String token) {
        if (token == null || token.isBlank()) return AS_PLACED;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.equals("random")) return RANDOM;
        try {
            int n = Integer.parseInt(t);
            return n >= 1 && n <= MAX ? new VariantCandles(n) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
