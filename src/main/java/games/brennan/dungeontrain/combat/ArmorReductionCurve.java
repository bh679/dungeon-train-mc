package games.brennan.dungeontrain.combat;

/**
 * How much of a hit armor blocks, replacing vanilla's hard 80% ceiling with a slow climb that never
 * reaches 90%.
 *
 * <p>Vanilla ({@code CombatRules.getDamageAfterAbsorb}) computes armor points as
 * {@code clamp(armor - damage / (2 + toughness / 4), armor * 0.2, 20)} and blocks {@code points / 25}
 * of the hit. AIS raises the armor attribute ceiling to 1024, but that clamp at 20 means anything past
 * 100 armor does nothing. This curve takes the same value <b>without</b> the upper clamp
 * ({@code raw}), so toughness and big-hit piercing behave exactly as before:</p>
 *
 * <ul>
 *   <li>{@code raw} 0–20: {@code raw / 25}, exactly vanilla.</li>
 *   <li>20–100: flat 80%, exactly vanilla.</li>
 *   <li>100–200: 80% → 83%, linear.</li>
 *   <li>200–400: 83% → 84%, linear.</li>
 *   <li>Above 400: a hyperbolic tail with the same slope at 400, approaching {@link #MAX_REDUCTION}
 *       and never reaching it (1024 → about 86%).</li>
 * </ul>
 *
 * <p>Applies to every living entity. Protection enchantments and Breach are applied by vanilla on
 * either side of this and are unchanged.</p>
 */
public final class ArmorReductionCurve {

    /** The reduction armor alone can never reach. */
    public static final float MAX_REDUCTION = 0.90f;

    /** Vanilla's armor-points divisor: 20 points / 25 = 80%. */
    static final float VANILLA_DIVIDER = 25.0f;
    static final float VANILLA_CAP_POINTS = 20.0f;
    static final float VANILLA_MAX_REDUCTION = VANILLA_CAP_POINTS / VANILLA_DIVIDER;

    static final float FLAT_END = 100.0f;
    static final float MID_POINT = 200.0f;
    static final float MID_REDUCTION = 0.83f;
    static final float TAIL_START = 400.0f;
    static final float TAIL_START_REDUCTION = 0.84f;

    /**
     * Tail shape: {@code MAX - (MAX - 0.84) * S / (S + raw - 400)}. Its slope at 400 is
     * {@code (MAX - 0.84) / S}, which equals the 200–400 segment's slope (0.01 / 200) when S = 1200.
     */
    static final float TAIL_SCALE = 1200.0f;

    private ArmorReductionCurve() {}

    /**
     * Fraction of the hit armor blocks for vanilla's unclamped armor points {@code raw}.
     * Always in [0, {@link #MAX_REDUCTION}). Non-finite or negative input blocks nothing.
     */
    public static float reduction(float raw) {
        if (!(raw > 0.0f)) return 0.0f;
        if (raw <= VANILLA_CAP_POINTS) return raw / VANILLA_DIVIDER;
        if (raw <= FLAT_END) return VANILLA_MAX_REDUCTION;
        if (raw <= MID_POINT) return lerp(raw, FLAT_END, MID_POINT, VANILLA_MAX_REDUCTION, MID_REDUCTION);
        if (raw <= TAIL_START) return lerp(raw, MID_POINT, TAIL_START, MID_REDUCTION, TAIL_START_REDUCTION);
        return tail(raw);
    }

    private static float tail(float raw) {
        double headroom = MAX_REDUCTION - TAIL_START_REDUCTION;
        double r = MAX_REDUCTION - headroom * TAIL_SCALE / (TAIL_SCALE + (double) raw - TAIL_START);
        // Infinity drives r to exactly MAX; keep the "never reaches 90%" promise in float.
        return Math.min((float) r, Math.nextDown(MAX_REDUCTION));
    }

    private static float lerp(float x, float x0, float x1, float y0, float y1) {
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
    }
}
