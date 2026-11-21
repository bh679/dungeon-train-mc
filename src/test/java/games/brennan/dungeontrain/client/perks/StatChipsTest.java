package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatChipsTest {

    @Test
    @DisplayName("numbers drop needless decimals; multipliers keep two places")
    void numbers() {
        assertEquals("10", StatChips.number(10.0));
        assertEquals("12.5", StatChips.number(12.5));
        assertEquals("22.6", StatChips.number(22.625));
        assertEquals("-14.8", StatChips.number(-14.8));
        assertEquals("1.25", StatChips.number(1.25, 100.0));
        assertEquals("1.5", StatChips.number(1.5, 100.0));
        assertEquals("2", StatChips.number(2.0, 100.0));
    }
}
