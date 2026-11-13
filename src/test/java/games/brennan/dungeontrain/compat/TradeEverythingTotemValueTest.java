package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TradeEverythingBridge#totemValue}: Dungeon Totem prices follow their loot weight. */
final class TradeEverythingTotemValueTest {

    private static final int VANILLA = 432; // TE 0.17.0's Totem of Undying, in sixteenths

    @Test
    @DisplayName("weights 5 / 8 / 10 / 15 price at 108 / 68 / 54 / 36 sixteenths")
    void tableValues() {
        assertEquals(108, TradeEverythingBridge.totemValue(5, 5, VANILLA));
        assertEquals(68, TradeEverythingBridge.totemValue(8, 5, VANILLA));
        assertEquals(54, TradeEverythingBridge.totemValue(10, 5, VANILLA));
        assertEquals(36, TradeEverythingBridge.totemValue(15, 5, VANILLA));
    }

    @Test
    @DisplayName("never above a quarter of a vanilla totem; a commoner totem is never worth more")
    void cappedAndMonotonic() {
        int last = Integer.MAX_VALUE;
        for (int w = 5; w <= 200; w++) {
            int v = TradeEverythingBridge.totemValue(w, 5, VANILLA);
            assertTrue(v <= VANILLA / 4, "weight " + w + " priced above a quarter");
            assertTrue(v <= last, "weight " + w + " priced above a rarer totem");
            assertTrue(v >= 1);
            last = v;
        }
    }
}
