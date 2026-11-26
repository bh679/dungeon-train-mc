package games.brennan.dungeontrain.perks.effects.lover;

/**
 * Spare Enemies numbers. The flee line is the share of max health a hostile mob must be left
 * under: 20% at point 1, then {@code 35% + 5% x log2(r / 2)} (35% at 2, 40% at 4, 45% at 8, 50% at
 * 16). It eases, never repeats a value and has no cap.
 */
public final class SpareEnemiesMath {

    /** Ticks a spared mob runs before it vanishes. */
    public static final int FLEE_TICKS = 120;
    /** A spared mob this far from the player who spared it vanishes at once. */
    public static final double VANISH_DISTANCE = 24.0;

    private SpareEnemiesMath() {}

    /** The flee line as a fraction of max health; 0 at rank 0. */
    public static double fleeLine(int rank) {
        if (rank <= 0) return 0.0;
        if (rank == 1) return 0.20;
        return 0.35 + 0.05 * (Math.log(rank / 2.0) / Math.log(2.0));
    }

    /** The flee line in percent, for the chip. */
    public static double fleePercent(int rank) {
        return fleeLine(rank) * 100.0;
    }

    /** True when a hit leaving {@code health} of {@code maxHealth} spares the mob at {@code rank}. */
    public static boolean spares(float health, float maxHealth, int rank) {
        if (health <= 0 || maxHealth <= 0 || rank <= 0) return false;
        return health / maxHealth < fleeLine(rank);
    }
}
