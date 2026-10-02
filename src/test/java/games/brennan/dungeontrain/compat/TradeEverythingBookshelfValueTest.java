package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TradeEverythingBridge#BOOKSHELF_VALUE_256THS} — four bookshelves pay
 * out one emerald after Trade Everything's payout margin, and that batch is
 * the row the villager quotes.
 */
final class TradeEverythingBookshelfValueTest {

    /** TE's default payout margin ({@code result_multiplier}). */
    private static final double PAYOUT_MARGIN = 0.75;
    /** One emerald in the 256ths the bookshelf value is given in. */
    private static final int EMERALD = 256;
    /** TE's {@code TradePricer.ACCEPTABLE_OVERPAY}: a batch losing more than this is skipped for a bigger one. */
    private static final double ACCEPTABLE_ROUNDING_LOSS = 0.10;

    private static double payout256ths(int bookshelves) {
        return bookshelves * TradeEverythingBridge.BOOKSHELF_VALUE_256THS * PAYOUT_MARGIN;
    }

    private static int payoutEmeralds(int bookshelves) {
        return (int) Math.floor(payout256ths(bookshelves) / EMERALD);
    }

    @Test
    @DisplayName("four bookshelves pay exactly 1 emerald")
    void fourPerEmerald() {
        assertEquals(1, payoutEmeralds(4));
    }

    @Test
    @DisplayName("three bookshelves or fewer pay no emerald")
    void fewerThanFourPayNothing() {
        assertEquals(0, payoutEmeralds(1));
        assertEquals(0, payoutEmeralds(2));
        assertEquals(0, payoutEmeralds(3));
    }

    @Test
    @DisplayName("the four-shelf batch rounds away little enough to be the quoted row")
    void fourShelfBatchIsQuoted() {
        double paid = payout256ths(4);
        double loss = (paid - EMERALD) / paid;
        assertTrue(loss <= ACCEPTABLE_ROUNDING_LOSS, "4 → 1 loses " + loss + ", TE would quote a bigger batch");
    }

    @Test
    @DisplayName("a full stack pays 16 emeralds")
    void fullStack() {
        assertEquals(16, payoutEmeralds(64));
    }
}
