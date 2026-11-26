package games.brennan.dungeontrain.perks.effects.alchemist;

/**
 * The pure numbers behind Standless Brewing (Alchemist tier 2), confirmed with Brennan 2026-11-26:
 * bottle slots {@code 1 + floor(r / 5)}, brew time {@code 20 s × 0.9^r}, and one free fuel every
 * {@code 5 min × 0.9^(r − 1)} up to a full bar of {@value #FUEL_MAX}. No caps: every curve keeps going.
 */
public final class PocketBrewingMath {

    /** A full fuel bar, as a vanilla brewing stand's: one blaze powder. */
    public static final int FUEL_MAX = 20;
    /** Vanilla's brew time: 20 s. */
    static final int VANILLA_BREW_TICKS = 400;
    static final double BREW_KEEP_PER_RANK = 0.9;
    /** The first free fuel's wait at 1 point: 5 min. */
    static final int FREE_FUEL_BASE_TICKS = 6000;
    static final double FREE_FUEL_KEEP_PER_RANK = 0.9;
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

    /** Ticks between free fuels at {@code rank}: 6000 at 1 point, × 0.9 each point after, at least one tick. */
    public static int freeFuelTicks(int rank) {
        int r = Math.max(1, rank);
        return Math.max(1, (int) Math.round(FREE_FUEL_BASE_TICKS * Math.pow(FREE_FUEL_KEEP_PER_RANK, r - 1)));
    }

    public static double brewSeconds(int rank) {
        return brewTicks(rank) / 20.0;
    }

    public static double freeFuelSeconds(int rank) {
        return freeFuelTicks(rank) / 20.0;
    }
}
