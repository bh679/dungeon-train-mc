package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CosmicDebrisDropsTest {

    private static final int GROUPS = 10_000;

    @Test
    @DisplayName("about one carriage group in five draws a debris")
    void rate() {
        int hits = 0;
        for (int g = -GROUPS / 2; g < GROUPS / 2; g++) if (CosmicDebrisDrops.groupGetsDebris(12345L, g)) hits++;
        double rate = hits / (double) GROUPS;
        assertTrue(Math.abs(rate - 0.2) < 0.015, "rate " + rate);
    }

    @Test
    @DisplayName("the draw is fixed per seed and group, and a new seed draws other groups")
    void deterministic() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        StringBuilder c = new StringBuilder();
        for (int g = 0; g < 200; g++) {
            a.append(CosmicDebrisDrops.groupGetsDebris(7L, g) ? '1' : '0');
            b.append(CosmicDebrisDrops.groupGetsDebris(7L, g) ? '1' : '0');
            c.append(CosmicDebrisDrops.groupGetsDebris(8L, g) ? '1' : '0');
        }
        assertEquals(a.toString(), b.toString());
        assertNotEquals(a.toString(), c.toString());
    }
}
