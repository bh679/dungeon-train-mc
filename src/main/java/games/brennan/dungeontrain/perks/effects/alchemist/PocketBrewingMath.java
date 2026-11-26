package games.brennan.dungeontrain.perks.effects.alchemist;

/**
 * The pure numbers behind Standless Brewing (Alchemist tier 2), confirmed with Brennan 2026-11-26:
 * bottle slots {@code 1 + floor(r / 5)} and brew time {@code 20 s × 0.9^r}. The stand needs no fuel.
 * No caps: every curve keeps going.
 */
public final class PocketBrewingMath {

    /** Vanilla's brew time: 20 s. */
    static final int VANILLA_BREW_TICKS = 400;
    static final double BREW_KEEP_PER_RANK = 0.9;
    /** One more bottle slot every this many points. */
    static final int POINTS_PER_SLOT = 5;

    private PocketBrewingMath() {}

    /** Bottle slots at {@code rank}: 1 from the first point, +1 every 5 points. 0 without the perk. */
    public static int bottleSlots(int rank) {
        if (rank <= 0) return 0;
        return 1 + rank / POINTS_PER_SLOT;
    }

    /** Ticks one brew takes at {@code rank}: 400 × 0.9^rank, at least one tick. */
    public static int brewTicks(int rank) {
        return Math.max(1, (int) Math.round(VANILLA_BREW_TICKS * Math.pow(BREW_KEEP_PER_RANK, Math.max(0, rank))));
    }

    public static double brewSeconds(int rank) {
        return brewTicks(rank) / 20.0;
    }
}
