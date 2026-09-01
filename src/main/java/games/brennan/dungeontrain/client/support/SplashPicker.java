package games.brennan.dungeontrain.client.support;

import java.util.List;

/**
 * How often the main menu's splash is one of ours — "122 changes this week!" — rather than one of
 * the hand-written quotes in {@code assets/minecraft/texts/splashes.txt}.
 *
 * <p>Each update line is weighted {@link #WEIGHT} against a single quote, so with four lines
 * available against nineteen quotes the pool is 20:19 and about half of menu visits name a figure.
 * The weight is per LINE, not per group: any one of them is five times likelier to come up than any
 * one quote.</p>
 *
 * <p>Pure and mixin-free so the arithmetic can be tested without a client — the mixin does nothing
 * but ask this class and hand the answer back to vanilla.</p>
 */
public final class SplashPicker {

    /** An update line's weight against one hand-written quote. */
    public static final int WEIGHT = 5;

    private SplashPicker() {}

    /**
     * Which update line to show, or {@code -1} to leave the splash to vanilla.
     *
     * @param available  how many update lines have something to say (0 hands it straight back)
     * @param quoteCount how many hand-written quotes are in the pool; a resource pack that empties
     *                   the file leaves only ours, which is fine — but a NEGATIVE count is nonsense
     *                   and is treated as none
     * @param roll       a uniform random in {@code [0, 1)}
     * @return an index into the available lines, or -1
     */
    public static int pick(int available, int quoteCount, double roll) {
        if (available <= 0) return -1;
        int quotes = Math.max(0, quoteCount);
        int ours = available * WEIGHT;
        int total = ours + quotes;
        // Scale the single roll across the whole weighted pool, then read off which slot it landed
        // in. One roll rather than two keeps the per-line odds exactly WEIGHT:1 against a quote.
        double slot = roll * total;
        if (slot >= ours) return -1;
        return Math.min(available - 1, (int) (slot / WEIGHT));
    }

    /** Convenience for the mixin: the chosen timeframe, or null to defer to vanilla. */
    public static UpdateStats.Timeframe choose(List<UpdateStats.Timeframe> available,
                                               int quoteCount, double roll) {
        int i = pick(available.size(), quoteCount, roll);
        return i < 0 ? null : available.get(i);
    }
}
