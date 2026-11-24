package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkRankCost;

/**
 * The Engine's rank-price maths, as the web page's test mode has it ({@code engineClock}): where the next
 * rank sits in its price run, and what a number of ranks cost from nothing. The menu no longer draws the
 * Engine; these stay for the rules they pin.
 */
final class EngineClock {

    private EngineClock() {}

    /** Where the next rank sits in its price run (web {@code priceProgress}). */
    record Progress(int price, int next, int left, int size, int done) {
        double fraction() {
            return size > 1 ? done / (double) size : 1.0;
        }
    }

    static Progress progress(int ranksBought) {
        int n = Math.max(0, ranksBought);
        int price = PerkRankCost.nextCost(n);
        int start = n;
        while (start > 0 && PerkRankCost.nextCost(start - 1) == price) start--;
        int end = n;
        while (PerkRankCost.nextCost(end + 1) == price) end++;
        return new Progress(price, PerkRankCost.nextCost(end + 1), end - n + 1, end - start + 1, n - start);
    }

    /** Points to buy {@code ranks} ranks from nothing (web {@code totalFor}). */
    static long totalFor(int ranks) {
        long sum = 0;
        for (int i = 0; i < ranks; i++) sum += PerkRankCost.nextCost(i);
        return sum;
    }
}
