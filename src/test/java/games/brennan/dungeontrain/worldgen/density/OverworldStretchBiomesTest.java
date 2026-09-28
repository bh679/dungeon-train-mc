package games.brennan.dungeontrain.worldgen.density;

import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context-free vanilla fallback: a marked overworld source with no published context must still
 * get a vanilla biome, never TerraBlender's pick (which can be Biomes O' Plenty).
 */
class OverworldStretchBiomesTest {

    private static final HolderOwner<Biome> OWNER = new HolderOwner<>() {};

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaTableIsTheOverworldPresetFilteredToMinecraft() {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> table = OverworldStretchBiomes.vanillaTable().values();
        assertFalse(table.isEmpty());
        assertTrue(table.stream().allMatch(p -> "minecraft".equals(p.getSecond().location().getNamespace())));

        long expected = MultiNoiseBiomeSourceParameterList.knownPresets()
                .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values().stream()
                .filter(p -> "minecraft".equals(p.getSecond().location().getNamespace()))
                .count();
        assertEquals(expected, table.size());
        assertSame(OverworldStretchBiomes.vanillaTable(), OverworldStretchBiomes.vanillaTable(), "cached");
    }

    @Test
    void fallbackPicksTheVanillaTableBiomeFromTheSourcesOwnHolders() {
        // A source like TerraBlender's: every vanilla biome plus a modded one.
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = new ArrayList<>();
        for (Pair<Climate.ParameterPoint, ResourceKey<Biome>> p : OverworldStretchBiomes.vanillaTable().values()) {
            points.add(Pair.of(p.getFirst(), Holder.Reference.createStandAlone(OWNER, p.getSecond())));
        }
        ResourceKey<Biome> modded = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "test_biome"));
        points.add(Pair.of(points.get(0).getFirst(), Holder.Reference.createStandAlone(OWNER, modded)));
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(points));

        Climate.Sampler sampler = Climate.empty();
        for (int q = -8; q <= 8; q += 4) {
            ResourceKey<Biome> expected = OverworldStretchBiomes.vanillaTable().findValue(sampler.sample(q, 16, q));
            Holder<Biome> got = OverworldStretchBiomes.vanillaFallback(source, q, 16, q, sampler);
            assertEquals(expected, got.unwrapKey().orElseThrow());
            assertTrue(source.possibleBiomes().contains(got), "holder comes from the source itself");
        }
    }

    @Test
    void fallbackReturnsNullWhenTheSourceLacksTheBiome() {
        ResourceKey<Biome> modded = ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath("biomesoplenty", "only_biome"));
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(List.of(
                Pair.of(OverworldStretchBiomes.vanillaTable().values().get(0).getFirst(),
                        Holder.Reference.createStandAlone(OWNER, modded)))));
        assertNull(OverworldStretchBiomes.vanillaFallback(source, 0, 16, 0, Climate.empty()));
    }
}
