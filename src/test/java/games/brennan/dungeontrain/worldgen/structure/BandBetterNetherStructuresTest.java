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
}
