package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.event.VanillaBackportSurfaceRuleCheck;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackportSurfaceRulesTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ResourceKey<NoiseGeneratorSettings> key(String ns, String path) {
        return ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath(ns, path));
    }

    @Test
    void onlyDtOverworldSettingsGetTheRule() {
        assertTrue(BackportSurfaceRules.isDtOverworld(key("dungeontrain", "overworld")));
        assertTrue(BackportSurfaceRules.isDtOverworld(key("dungeontrain", "overworld_y-64")));
        assertFalse(BackportSurfaceRules.isDtOverworld(key("minecraft", "overworld")));
        assertFalse(BackportSurfaceRules.isDtOverworld(key("dungeontrain", "nether")));
        assertFalse(BackportSurfaceRules.isDtOverworld(null));
    }

    @Test
    void mergedRuleNamesSulfurCaves() {
        SurfaceRules.RuleSource dtRule = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        assertFalse(VanillaBackportSurfaceRuleCheck.mentionsBiome(dtRule, BackportBiomes.SULFUR_CAVES));
        assertTrue(VanillaBackportSurfaceRuleCheck.mentionsBiome(
                BackportSurfaceRules.withSulfur(dtRule), BackportBiomes.SULFUR_CAVES));
    }

    @Test
    void theSameRuleMergesOnce() {
        SurfaceRules.RuleSource dtRule = SurfaceRules.state(Blocks.DIRT.defaultBlockState());
        assertSame(BackportSurfaceRules.withSulfur(dtRule), BackportSurfaceRules.withSulfur(dtRule));
    }
}
