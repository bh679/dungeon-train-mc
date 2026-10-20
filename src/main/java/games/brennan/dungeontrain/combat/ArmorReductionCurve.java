package games.brennan.dungeontrain.combat;

/**
 * How much of a hit armor blocks, replacing vanilla's hard 80% ceiling with a slow climb that never
 * reaches 90%.
 *
 * <p>Vanilla ({@code CombatRules.getDamageAfterAbsorb}) computes armor points as
 * {@code clamp(armor - damage / (2 + toughness / 4), armor * 0.2, 20)} and blocks {@code points / 25}
 * of the hit. AIS raises the armor and toughness attribute ceilings to 1024, but that clamp at 20 means
 * nothing past it helps. Here {@code raw} is the same value <b>without</b> the upper clamp:</p>
 *
 * <ul>
 *   <li>{@code raw} up to 20: {@code raw / 25}, exactly vanilla. Toughness only stops big hits
 *       piercing, as in vanilla.</li>
 *   <li>Past 20, toughness also counts toward the curve, one armor point per point of toughness beyond
 *       what vanilla needed to reach 20 ({@link #toughnessToReachCap}), so nothing jumps at 80%.</li>
 *   <li>Curve input {@code x} past 20 climbs very slowly, one hyperbola from 80% toward
 *       {@link #MAX_REDUCTION} that never reaches it: {@code 0.90 - 0.10 * S / (S + x - 20)},
 *       S = {@link #CLIMB_SCALE}. 120 is about 80.3%, 520 about 81.4%, 1044 about 82.5%,
 *       2048 (AIS's armor and toughness caps together) about 84%.</li>
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

    /** How slowly the climb past 80% goes: input {@code 20 + S} sits halfway between 80% and 90%. */
    static final float CLIMB_SCALE = 3000.0f;

    private ArmorReductionCurve() {}

    /**
     * Fraction of a {@code damage} hit blocked by {@code armor} and {@code toughness}.
     * Always in [0, {@link #MAX_REDUCTION}). Non-finite or negative stats block nothing extra.
     */
    public static float reduction(float armor, float toughness, float damage) {
        float t = toughness > 0.0f ? toughness : 0.0f;
        float d = damage > 0.0f ? damage : 0.0f;
        float raw = Math.max(armor - d / (2.0f + t / 4.0f), armor * 0.2f);
        if (!(raw > 0.0f)) return 0.0f;
        if (raw <= VANILLA_CAP_POINTS) return raw / VANILLA_DIVIDER;
        float spareToughness = Math.max(0.0f, t - toughnessToReachCap(armor, d));
        return curve(raw + spareToughness);
    }

    /**
     * Toughness vanilla needs for a {@code damage} hit to leave {@code armor} at the 20-point cap:
     * solves {@code armor - damage / (2 + t / 4) = 20}, ignoring the {@code armor * 0.2} floor so it
     * stays smooth as armor crosses 100 (when the floor carries a huge hit past 20, toughness only
     * starts counting once it would have got there by itself). Infinite at 20 armor or below.
     */
    static float toughnessToReachCap(float armor, float damage) {
        float headroom = armor - VANILLA_CAP_POINTS;
        if (!(headroom > 0.0f)) return Float.POSITIVE_INFINITY;
        return Math.max(0.0f, 4.0f * (damage / headroom - 2.0f));
    }

    /** Reduction for curve input {@code x} (armor points, plus spare toughness) at or past 20. */
    static float curve(float x) {
        if (!(x > VANILLA_CAP_POINTS)) return VANILLA_MAX_REDUCTION;
        double headroom = MAX_REDUCTION - VANILLA_MAX_REDUCTION;
        double r = MAX_REDUCTION - headroom * CLIMB_SCALE / (CLIMB_SCALE + (double) x - VANILLA_CAP_POINTS);
        // Infinity drives r to exactly MAX; keep the "never reaches 90%" promise in float.
        return Math.min((float) r, Math.nextDown(MAX_REDUCTION));
    }
}
