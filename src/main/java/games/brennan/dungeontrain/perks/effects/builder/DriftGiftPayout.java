package games.brennan.dungeontrain.perks.effects.builder;

/**
 * The arithmetic of <b>Drift Gift Repaid</b> ({@value #ID}): what a gift pays, in sixteenths of an
 * emerald (Trade Everything's unit, 16 = one emerald).
 *
 * <p>Brennan's note: 35% of the item's trade value, +5% per point. Read here as rank 1 = 35% and each
 * further point +5%, capped at {@link #MAX_RATE}. Whether the 35% should instead be the rank 0 base
 * (so rank 1 = 40%) is a balancing call flagged in the Gate 2 report.</p>
 */
public final class DriftGiftPayout {

    public static final String ID = "drift-gift-repaid";

    /** Payout at rank 1. */
    public static final double BASE_RATE = 0.35;
    /** Added per active point beyond the first. */
    public static final double RATE_PER_EXTRA_RANK = 0.05;
    /** Never pay more than this share of the trade value. */
    public static final double MAX_RATE = 0.90;

    /** Sixteenths in one emerald. */
    public static final int SIXTEENTHS_PER_EMERALD = 16;

    private DriftGiftPayout() {}

    /** The payout rate for {@code rank}; 0 when the perk is not active. */
    public static double rate(int rank) {
        if (rank <= 0) return 0.0;
        return Math.min(MAX_RATE, BASE_RATE + RATE_PER_EXTRA_RANK * (rank - 1));
    }

    /** {@link #rate} as a whole percentage for the slot tooltip. */
    public static int ratePercent(int rank) {
        return (int) Math.round(rate(rank) * 100.0);
    }

    /**
     * Sixteenths paid for ONE item worth {@code valueSixteenths}, floored. Zero for worthless items
     * and for rank 0. Per item (not per stack) so a partial pickup can be charged back exactly.
     */
    public static int perItem(int valueSixteenths, int rank) {
        if (valueSixteenths <= 0) return 0;
        return (int) Math.floor(valueSixteenths * rate(rank));
    }

    /** Whole emeralds in {@code sixteenths}. */
    public static int emeralds(long sixteenths) {
        return (int) Math.max(0, sixteenths / SIXTEENTHS_PER_EMERALD);
    }

    /** The part of {@code sixteenths} short of a whole emerald, carried in the ledger. */
    public static int remainder(long sixteenths) {
        return (int) Math.max(0, sixteenths % SIXTEENTHS_PER_EMERALD);
    }

    /** Emeralds that must be handed over to cover a {@code shortfall} of sixteenths (ceil). */
    public static int emeraldsToCover(long shortfall) {
        if (shortfall <= 0) return 0;
        return (int) ((shortfall + SIXTEENTHS_PER_EMERALD - 1) / SIXTEENTHS_PER_EMERALD);
    }
}
