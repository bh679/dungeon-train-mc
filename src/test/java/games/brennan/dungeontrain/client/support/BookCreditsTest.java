package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The {@code /books/credits} answer: a balance, or -1 when the reply is not one. */
class BookCreditsTest {

    @Test
    void readsTheBalanceAndRefusesAnythingElse() {
        assertEquals(3, BookCredits.parseBalance("{\"ok\":true,\"balance\":3,\"granted\":3,\"spent\":0}"));
        assertEquals(0, BookCredits.parseBalance("{\"ok\":true,\"balance\":-2}"));
        assertEquals(-1, BookCredits.parseBalance("{\"error\":\"credits_disabled\"}"));
        assertEquals(-1, BookCredits.parseBalance("{\"ok\":true}"));
        assertEquals(-1, BookCredits.parseBalance(""));
        assertEquals(-1, BookCredits.parseBalance(null));
    }

    @Test
    void readsBoostsLeftOnlyFromARelayThatSendsThem() {
        assertEquals(25, BookCredits.parseBoostsLeft("{\"ok\":true,\"balance\":2,\"boostsLeft\":25}"));
        assertEquals(-1, BookCredits.parseBoostsLeft("{\"ok\":true,\"balance\":2}"));
        assertEquals(0, BookCredits.parseBoostsLeft("{\"ok\":true,\"boostsLeft\":-3}"));
        assertEquals(-1, BookCredits.parseBoostsLeft("{\"ok\":true,\"boostsLeft\":\"lots\"}"));
        assertEquals(-1, BookCredits.parseBoostsLeft(null));
    }
}
