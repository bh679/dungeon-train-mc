package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.compat.DisabledModContent;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandBetterNetherStructuresTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("band column: solid core and everything outside the slab read netherrack; open core is lava below the sea, else air")
    void bandColumnLayout() {
        assertEquals(Blocks.NETHERRACK.defaultBlockState(), BandBetterNetherStructures.stateAt(false, false, false));
        assertEquals(Blocks.NETHERRACK.defaultBlockState(), BandBetterNetherStructures.stateAt(false, false, true));
        assertEquals(Blocks.NETHERRACK.defaultBlockState(), BandBetterNetherStructures.stateAt(true, true, true));
        assertEquals(Blocks.LAVA.defaultBlockState(), BandBetterNetherStructures.stateAt(true, false, true));
        assertEquals(Blocks.AIR.defaultBlockState(), BandBetterNetherStructures.stateAt(true, false, false));
    }

    @Test
    @DisplayName("outside a scope every hook is a pass-through, and a scope never outlives its body")
    void scopeLeavesNoState() {
        assertTrue(BandBetterNetherStructures.scopeClear());
        assertFalse(BandBetterNetherStructures.inScope());
        assertEquals(40, BandBetterNetherStructures.cityCenterY(40));
        assertNull(BandBetterNetherStructures.scopedBiome(0, 0));
        assertNull(BandBetterNetherStructures.bandColumn(0, 0, null));

        BandBetterNetherStructures.Scope scope = new BandBetterNetherStructures.Scope(null);
        assertTrue(BandBetterNetherStructures.runInForTest(scope, BandBetterNetherStructures::inScope));
        assertThrows(IllegalStateException.class, () -> BandBetterNetherStructures.runInForTest(scope, () -> {
            throw new IllegalStateException("siting failed");
        }));
        assertTrue(BandBetterNetherStructures.scopeClear());
    }

    @Test
    @DisplayName("BetterNether's ore blocks are disabled in structures; its decor blocks and vanilla ores are not")
    void disabledOreBlocks() {
        assertTrue(DisabledModContent.isDisabledOreBlock(ResourceLocation.parse("betternether:cincinnasite_ore")));
        assertTrue(DisabledModContent.isDisabledOreBlock(ResourceLocation.parse("betternether:nether_ruby_ore")));
        assertFalse(DisabledModContent.isDisabledOreBlock(ResourceLocation.parse("betternether:cincinnasite_forged")));
        assertFalse(DisabledModContent.isDisabledOreBlock(ResourceLocation.parse("minecraft:nether_gold_ore")));
        assertFalse(DisabledModContent.isDisabledOreBlock(null));
    }

    @Test
    @DisplayName("half of BetterNether's attempts are kept, the same ones for the same seed")
    void keepsHalf() {
        int kept = 0;
        int total = 0;
        for (int cx = -200; cx < 200; cx++) {
            for (int cz = -50; cz < 50; cz++) {
                boolean k = BandBetterNetherStructures.keeps(1234L, "betternether:altars", cx, cz);
                assertEquals(k, BandBetterNetherStructures.keeps(1234L, "betternether:altars", cx, cz));
                if (k) kept++;
                total++;
            }
        }
        double share = kept / (double) total;
        assertTrue(Math.abs(share - BandBetterNetherStructures.BAND_KEEP_CHANCE) < 0.01, "kept share " + share);
    }

    @Test
    @DisplayName("searched types try the chunk middle first, then a 5x5 grid of nearby spots within 8 blocks")
    void spotGrid() {
        var spots = BandBetterNetherStructures.SPOT_OFFSETS;
        assertEquals(25, spots.size());
        assertEquals(0, spots.get(0)[0]);
        assertEquals(0, spots.get(0)[1]);
        for (int[] s : spots) {
            assertTrue(Math.abs(s[0]) <= 8 && Math.abs(s[1]) <= 8);
        }
        assertTrue(BandBetterNetherStructures.SEARCHED.containsAll(
                java.util.Set.of("pyramid", "jungle_temples", "gardens", "portals", "spawn_altar_ladder")));
    }
}
