package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rare-find list Brennan picked (2026-11-07), and which vaults number their finds. */
final class VaultRarityTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    @Test
    @DisplayName("mace, heavy core, trident and every spawn egg are rare finds")
    void rareFinds() {
        assertTrue(VaultRarity.isRare(id("minecraft:mace")));
        assertTrue(VaultRarity.isRare(id("minecraft:heavy_core")));
        assertTrue(VaultRarity.isRare(id("minecraft:trident")));
        assertTrue(VaultRarity.isRare(id("minecraft:fox_spawn_egg")));
        assertTrue(VaultRarity.isRare(id("minecraft:creaking_spawn_egg")));
    }

    @Test
    @DisplayName("netherite and the Vista mirror were left out on purpose, as is everyday loot")
    void notRare() {
        assertFalse(VaultRarity.isRare(id("minecraft:netherite_sword")));
        assertFalse(VaultRarity.isRare(id("minecraft:netherite_block")));
        assertFalse(VaultRarity.isRare(id("minecraft:netherite_ingot")));
        assertFalse(VaultRarity.isRare(id("vista:mirror")));
        assertFalse(VaultRarity.isRare(id("minecraft:diamond")));
        assertFalse(VaultRarity.isRare(id("minecraft:spawner")));
    }

    @Test
    @DisplayName("ominous finds are numbered; a plain trial vault's are only counted")
    void numberedVaults() {
        var trial = Blocks.VAULT.defaultBlockState();
        var ominous = trial.setValue(VaultBlock.OMINOUS, true);
        assertEquals(VaultRarity.Kind.TRIAL, VaultRarity.kindOf(trial));
        assertEquals(VaultRarity.Kind.OMINOUS, VaultRarity.kindOf(ominous));
        assertFalse(VaultRarity.Kind.TRIAL.numbered());
        assertTrue(VaultRarity.Kind.OMINOUS.numbered());
        assertTrue(VaultRarity.Kind.GREEN.numbered());
        assertEquals("green", VaultRarity.Kind.GREEN.wire());
    }
}
