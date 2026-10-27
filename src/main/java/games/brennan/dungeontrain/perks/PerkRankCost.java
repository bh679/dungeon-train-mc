package games.brennan.dungeontrain.perks;

/**
 * What the next perk rank costs in active points (Cosmic Debris), counted over every rank bought this
 * life on any perk. Tier does not change it: tier is paid once, in Advancement Points, to unlock.
 *
 * <p>The price holds for a run of ranks that shrinks by one each time: 10 ranks at 1, 9 at 2, 8 at 3 …
 * 2 at 9 and 1 at 10. A shrinking run keeps each rise felt (1 to 2 doubles the price, 9 to 10 would
 * barely register if it lasted as long). From there every rank costs more than the last by a jump that
 * grows by one: 11, 13, 16, 20 … Death clears the ranks, so a new life starts at 1 again.
 * See {@code docs/design/perks-earning.md}.</p>
 */
public final class PerkRankCost {

    /** Ranks bought at a price of 1; each later price holds for one rank fewer. */
    static final int FIRST_RUN = 10;

    private PerkRankCost() {}

    /** Price of the next rank when {@code ranksBought} ranks are already held this life. */
    public static int nextCost(int ranksBought) {
        int remaining = Math.max(0, ranksBought);
        int price = 1;
        for (int run = FIRST_RUN; run > 1; run--) {
            if (remaining < run) return price;
            remaining -= run;
            price++;
        }
        // Past the shrinking runs: the price after `remaining` single-rank steps of growing jumps.
        long grown = (long) price + (long) remaining * (remaining + 1) / 2;
        return (int) Math.min(Integer.MAX_VALUE, grown);
    }

    /** Active points paid for {@code ranksBought} ranks bought one after another from a fresh life. */
    public static long totalCost(int ranksBought) {
        long sum = 0;
        for (int i = 0; i < ranksBought; i++) sum += nextCost(i);
        return sum;
    }

    /** Ranks bought this life, across every perk. */
    public static int ranksBought(PerkState state) {
        int sum = 0;
        for (int r : state.active().values()) sum += Math.max(0, r);
        return sum;
    }

    public static int nextCost(PerkState state) {
        return nextCost(ranksBought(state));
    }
}
