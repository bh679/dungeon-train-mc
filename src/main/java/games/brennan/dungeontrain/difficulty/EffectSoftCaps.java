package games.brennan.dungeontrain.difficulty;

/**
 * Soft caps for the two potion effects whose vanilla curve tops out: Resistance V+ blocks every
 * hit and Regeneration VI+ heals every tick. Deep-train mobs roll both far past those levels, so
 * from level V up each effect follows a curve that keeps improving but never reaches its limit.
 * Levels I-IV are vanilla. Applied to every entity (players only reach V+ through commands) by
 * {@code event.ResistanceSoftCapEvents} and {@code mixin.RegenerationSoftCapMixin}.
 */
public final class EffectSoftCaps {

    /** First amplifier (level V) that leaves the vanilla curve. */
    public static final int SOFT_CAP_FROM_AMPLIFIER = 4;

    /** Fraction of damage vanilla Resistance IV lets through; the soft curve starts here. */
    private static final double RESISTANCE_IV_DAMAGE_TAKEN = 0.8;

    /** Healing rate the soft Regeneration curve approaches but never reaches. */
    public static final double REGEN_CEILING_HP_PER_SEC = 10.0;

    /** Vanilla Regeneration IV heals 1 HP every {@code 50 >> 3} = 6 ticks: 3.33 HP/s. */
    private static final double REGEN_IV_HP_PER_SEC = 20.0 / 6.0;

    private EffectSoftCaps() {}

    /** True when {@code amplifier} uses a soft curve instead of vanilla's. */
    public static boolean isSoftCapped(int amplifier) {
        return amplifier >= SOFT_CAP_FROM_AMPLIFIER;
    }

    /**
     * Fraction of incoming damage a Resistance {@code amplifier} lets through: 0.8 / level, so
     * level IV's 20% continues as V 16%, X 8%, XX 4%, and never reaches 0.
     */
    public static double resistanceDamageFraction(int amplifier) {
        int level = amplifier + 1;
        return RESISTANCE_IV_DAMAGE_TAKEN / level;
    }

    /**
     * HP per second a Regeneration {@code amplifier} heals: rises from level IV's 3.33 towards
     * {@link #REGEN_CEILING_HP_PER_SEC} as 10 - 6.67 * 4 / level (V 4.7, X 7.3, XX 8.7).
     */
    public static double regenHealPerSecond(int amplifier) {
        int level = amplifier + 1;
        double gap = REGEN_CEILING_HP_PER_SEC - REGEN_IV_HP_PER_SEC;
        return REGEN_CEILING_HP_PER_SEC - gap * SOFT_CAP_FROM_AMPLIFIER / level;
    }
}
