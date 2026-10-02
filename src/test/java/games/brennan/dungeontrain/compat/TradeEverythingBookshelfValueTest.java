package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TradeEverythingBridge#BOOKSHELF_VALUE_SIXTEENTHS} — four bookshelves
 * pay out one emerald after Trade Everything's payout margin.
 */
final class TradeEverythingBookshelfValueTest {

    /** TE's default payout margin ({@code result_multiplier}). */
    private static final double PAYOUT_MARGIN = 0.75;

    private static int payoutEmeralds(int bookshelves) {
        int sixteenths = bookshelves * TradeEverythingBridge.BOOKSHELF_VALUE_SIXTEENTHS;
        return (int) Math.floor(sixteenths * PAYOUT_MARGIN / 16.0);
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
    @DisplayName("a full stack pays 18 emeralds")
    void fullStack() {
        assertEquals(18, payoutEmeralds(64));
    }
}
