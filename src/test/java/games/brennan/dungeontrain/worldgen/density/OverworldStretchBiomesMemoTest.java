package games.brennan.dungeontrain.worldgen.density;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Lifecycle;
import games.brennan.dungeontrain.worldgen.VanillaWorldgenLookup;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import terrablender.api.Region;
import terrablender.api.RegionType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band context republishes on every dimension load and again at server start; each republish must
 * reuse the stretch tables built from the same registry and regions rather than rebuild them. The
 * vanilla worldgen lookup those builds compare against is built once per JVM for the same reason.
 */
class OverworldStretchBiomesMemoTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearMemo() {
        OverworldStretchBiomes.clear();
    }

    @Test
    void repeatedResolveOnTheSameInputsReturnsTheSameInstance() {
        Registry<Biome> biomes = vanillaBiomeRegistry();
        Region bop = bopRegion("memo_test");

        OverworldStretchBiomes first = OverworldStretchBiomes.resolve(biomes, List.of(bop));
        // TerraBlender's Regions.get copies its list on every call: a new list, the same regions.
        OverworldStretchBiomes second = OverworldStretchBiomes.resolve(biomes, new ArrayList<>(List.of(bop)));

        assertNotNull(first);
        assertEquals(1, first.bopRegionCount());
        assertSame(first, second);
    }

    @Test
    void aNewRegistryOrRegionSetRebuilds() {
        Registry<Biome> biomes = vanillaBiomeRegistry();
        Region bop = bopRegion("memo_test");
        OverworldStretchBiomes first = OverworldStretchBiomes.resolve(biomes, List.of(bop));

        assertNotSame(first, OverworldStretchBiomes.resolve(vanillaBiomeRegistry(), List.of(bop)), "new registry");
        OverworldStretchBiomes other = OverworldStretchBiomes.resolve(biomes, List.of(bopRegion("memo_test")));
        assertNotSame(first, other, "an equal-named but different region object");
        assertEquals(0, OverworldStretchBiomes.resolve(biomes, List.of()).bopRegionCount(), "no regions");
    }

    @Test
    void clearDropsTheCachedBuild() {
        Registry<Biome> biomes = vanillaBiomeRegistry();
        Region bop = bopRegion("memo_test");
        OverworldStretchBiomes first = OverworldStretchBiomes.resolve(biomes, List.of(bop));
        OverworldStretchBiomes.clear();
        assertNotSame(first, OverworldStretchBiomes.resolve(biomes, List.of(bop)));
    }

    @Test
    void vanillaWorldgenLookupIsBuiltOnce() {
        HolderLookup.Provider lookup = VanillaWorldgenLookup.get();
        assertSame(lookup, VanillaWorldgenLookup.get());
        assertTrue(lookup.lookupOrThrow(Registries.BIOME).get(Biomes.PLAINS).isPresent());
    }

    /** A fresh live-style biome registry holding every vanilla biome. */
    private static Registry<Biome> vanillaBiomeRegistry() {
        MappedRegistry<Biome> registry = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaWorldgenLookup.get().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(ref -> Registry.register(registry, ref.key(), ref.value()));
        return registry;
    }

    /** A Biomes O' Plenty-namespaced region with one point (a vanilla biome, so it resolves). */
    private static Region bopRegion(String path) {
        return new Region(ResourceLocation.fromNamespaceAndPath("biomesoplenty", path), RegionType.OVERWORLD, 1) {
            @Override
            public void addBiomes(Registry<Biome> registry,
                                  Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
                mapper.accept(Pair.of(OverworldStretchBiomes.vanillaTable().values().get(0).getFirst(), Biomes.FOREST));
            }
        };
    }
}
