package games.brennan.dungeontrain.perks.effects.archivist;

/**
 * Extra Cam-Shots: a disposable camera gets a 50% chance per rank of one more shot, stacking into
 * guaranteed shots: rank 2 is one extra, rank 3 one extra plus a coin flip for a second, rank 4 two.
 * Rolled once per camera, when it takes its first shot. Pure.
 */
public final class ExtraCamShots {

    /** Chance of one extra shot per rank. Balancing knob. */
    public static final float CHANCE_PER_RANK = 0.5f;

    private ExtraCamShots() {}

    /** Extra shots for {@code rank}, given one uniform {@code roll} in [0, 1). 0 for rank 0 or less. */
    public static int extraShots(int rank, float roll) {
        if (rank <= 0) return 0;
        float chance = rank * CHANCE_PER_RANK;
        int guaranteed = (int) Math.floor(chance);
        float remainder = chance - guaranteed;
        return guaranteed + (remainder > 0f && roll < remainder ? 1 : 0);
    }
}
