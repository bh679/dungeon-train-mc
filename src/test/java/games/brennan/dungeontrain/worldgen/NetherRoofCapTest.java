package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The band's roof ends like the real Nether: netherrack under vanilla's ragged bedrock ceiling. */
final class NetherRoofCapTest {

    private static final long SEED = 424242L;

    @Test
    @DisplayName("the cap fills Nether y121..127, the rows above the sampled slab")
    void capRows() {
        assertEquals(7, NetherRoofCap.ROWS);
        assertEquals(156, NetherCoreGeometry.coreTopY(76));
    }

    @Test
    @DisplayName("bedrock chance follows vanilla's bedrock_roof gradient: 0, 0, 20, 40, 60, 80, 100%")
    void vanillaGradient() {
        double[] expected = {0.0, 0.0, 0.2, 0.4, 0.6, 0.8, 1.0};
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], NetherRoofCap.bedrockChance(121 + i), 1e-9, "Nether y" + (121 + i));
        }
    }

    @Test
    @DisplayName("the top row is always bedrock, the bottom two never; the fringe matches its odds")
    void drawsMatchTheGradient() {
        for (int netherY = 121; netherY <= 127; netherY++) {
            int hits = 0;
            int n = 0;
            for (int x = 0; x < 100; x++) {
                for (int z = 0; z < 100; z++) {
                    if (NetherRoofCap.isBedrock(SEED, x, 150 + netherY - 120, z, netherY)) hits++;
                    n++;
                }
            }
            double share = (double) hits / n;
            assertEquals(NetherRoofCap.bedrockChance(netherY), share, 0.02, "Nether y" + netherY);
        }
    }

    @Test
    @DisplayName("the draw is seed-stable per block")
    void deterministic() {
        boolean a = NetherRoofCap.isBedrock(SEED, 1832, 160, -40, 124);
        for (int i = 0; i < 5; i++) assertEquals(a, NetherRoofCap.isBedrock(SEED, 1832, 160, -40, 124));
        assertTrue(NetherRoofCap.isBedrock(SEED, 5, 163, 5, 127));
        assertFalse(NetherRoofCap.isBedrock(SEED, 5, 157, 5, 121));
    }
}
