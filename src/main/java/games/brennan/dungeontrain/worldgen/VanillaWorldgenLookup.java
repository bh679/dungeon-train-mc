package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.biome.BiomeData;
import net.minecraft.data.worldgen.Carvers;
import net.minecraft.data.worldgen.ProcessorLists;
import net.minecraft.data.worldgen.features.FeatureUtils;
import net.minecraft.data.worldgen.placement.PlacementUtils;

/**
 * Vanilla's biomes, placed/configured features and carvers rebuilt from code — the reference the WWOO
 * confinement compares the live (datapack-rewritten) registries against. Datapacks can't touch it.
 *
 * <p>A subset of {@code VanillaRegistries.createLookup()} on purpose: the full builder also bootstraps
 * the multi-noise biome-source parameter lists, which WorldWeaver (BCLib/BetterNether/BetterEnd)
 * patches to reference its own biomes — and those aren't in vanilla's biome bootstrap, so the full
 * build fails with "Unreferenced key … betternether:…". These five registries are self-contained.</p>
 */
public final class VanillaWorldgenLookup {

    private VanillaWorldgenLookup() {}

    public static HolderLookup.Provider create() {
        RegistrySetBuilder builder = new RegistrySetBuilder()
                .add(Registries.CONFIGURED_CARVER, Carvers::bootstrap)
                .add(Registries.PROCESSOR_LIST, ProcessorLists::bootstrap) // fossil features reference these
                .add(Registries.CONFIGURED_FEATURE, FeatureUtils::bootstrap)
                .add(Registries.PLACED_FEATURE, PlacementUtils::bootstrap)
                .add(Registries.BIOME, BiomeData::bootstrap);
        return builder.build(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
