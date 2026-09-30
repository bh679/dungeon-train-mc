package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.worldgen.BackportBiomes;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup check that VanillaBackport's sulfur-cave surface rule reached DT's overworld settings. */
final class VanillaBackportSurfaceRuleCheckTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SurfaceRules.RuleSource stoneIn(net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> biome) {
        return SurfaceRules.ifTrue(SurfaceRules.isBiome(biome), SurfaceRules.state(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("finds the sulfur-caves branch at the head of a sequence, or nested")
    void findsTheBackportBranch() {
        SurfaceRules.RuleSource dtRule = SurfaceRules.sequence(stoneIn(Biomes.PLAINS),
                SurfaceRules.state(Blocks.DIRT.defaultBlockState()));
        assertTrue(VanillaBackportSurfaceRuleCheck.mentionsBiome(
                SurfaceRules.sequence(stoneIn(BackportBiomes.SULFUR_CAVES), dtRule), BackportBiomes.SULFUR_CAVES));
        assertTrue(VanillaBackportSurfaceRuleCheck.mentionsBiome(
                SurfaceRules.sequence(dtRule, SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
                        stoneIn(BackportBiomes.SULFUR_CAVES))), BackportBiomes.SULFUR_CAVES));
    }

    @Test
    @DisplayName("a rule without it (or no rule) is reported missing")
    void reportsItMissing() {
        SurfaceRules.RuleSource dtRule = SurfaceRules.sequence(stoneIn(Biomes.PLAINS), stoneIn(Biomes.DEEP_DARK));
        assertFalse(VanillaBackportSurfaceRuleCheck.mentionsBiome(dtRule, BackportBiomes.SULFUR_CAVES));
        assertFalse(VanillaBackportSurfaceRuleCheck.mentionsBiome(null, BackportBiomes.SULFUR_CAVES));
    }
}
